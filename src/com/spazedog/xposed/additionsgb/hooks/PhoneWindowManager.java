package com.spazedog.xposed.additionsgb.hooks;

import java.lang.ref.WeakReference;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.Surface;
import android.widget.Toast;

import com.android.internal.statusbar.IStatusBarService;
import com.spazedog.xposed.additionsgb.Common;
import com.spazedog.xposed.additionsgb.tools.XposedTools;

import de.robv.android.xposed.XC_MethodHook;

public class PhoneWindowManager extends XC_MethodHook {
	
	public static final String TAG = Common.PACKAGE_NAME + "$PhoneWindowManager";
	
	public static final Boolean DEBUG = Common.DEBUG;
	
	private PhoneWindowManager() {
		resetEvent();
	}
	
	public static void inject() {
		Class<?> phoneWindowManagerClass = XposedTools.findClass("com.android.internal.policy.impl.PhoneWindowManager");
			
		PhoneWindowManager instance = new PhoneWindowManager();
			
		XposedTools.hookConstructors(phoneWindowManagerClass, instance);
		XposedTools.hookMethods(phoneWindowManagerClass, "init", instance);
		XposedTools.hookMethods(phoneWindowManagerClass, "interceptKeyBeforeQueueing", instance);
		XposedTools.hookMethods(phoneWindowManagerClass, "interceptKeyBeforeDispatching", instance);
	}
	
	@Override
	protected final void beforeHookedMethod(final MethodHookParam param) {
		if (param.method instanceof Method) {
			if (((Method) param.method).getName().equals("interceptKeyBeforeQueueing")) {
				hook_interceptKeyBeforeQueueing(param);
				
			} else if (((Method) param.method).getName().equals("interceptKeyBeforeDispatching")) {
				hook_interceptKeyBeforeDispatching(param);
			}
			
		} else {
			hook_construct(param);
		}
	}
	
	@Override
	protected final void afterHookedMethod(final MethodHookParam param) {
		if (param.method instanceof Method) {
			if (((Method) param.method).getName().equals("init")) {
				hook_init(param);
			}
		}
	}
	
	//when hook is constructed, so used both before queue/dispatch
	private Class<?> mActivityManagerNativeClass;
	private Class<?> mWindowManagerPolicyClass; 
	private Class<?> mServiceManagerClass;
	private Class<?> mInputManagerClass;
	private Class<?> mProcessClass;
	private Class<?> mWindowStateClass;
		
	private static int FLAG_INJECTED;
	private static int FLAG_VIRTUAL;
	private static int ACTION_DISPATCH;
	
	private final int ACTION_DISABLE = 0;
	private final Object ACTION_DISPATCH_DISABLED = (SDK_NUMBER <= 10 ? true : -1);
	
	private static int INJECT_INPUT_EVENT_MODE_ASYNC;
	
	private static final int SDK_NUMBER = android.os.Build.VERSION.SDK_INT;
	
	private WeakReference<Object> mHookedReference;
	
	private Context mContext;
	private Object mWindowManager;
	private PowerManager mPowerManager;
	private WakeLock mWakeLock;
	private WakeLock mWakeLockPartial;
	
    protected static Boolean mBootCompleted = false;
    protected static Boolean mInterceptKeycode = false; //Intercept when configuring
	
	private Object mRecentAppsTrigger;
	
	//status for current event
	
	protected final Flags mKeyFlags = new Flags();
	protected class Flags {
		protected int mKeyPrimary = 0;
		protected int mKeySecondary = 0;
		protected int mKeyRepeat = 0;
		protected Boolean mWasScreenOn = true;
		
		//public volatile Boolean DOWN; //Directions of key, could be overridden
		public volatile Boolean ACTION_DONE; //Cancel processing of further events for event (except pending up keys)
		//public volatile Boolean RESET; //Set when action is done and when no action is possible
		//public volatile Boolean ONGOING; //Any pending events
		//public volatile Boolean LONGPRESS_DEFAULT_ACTION; //default handling for key event (set on first down)
		//public volatile Boolean REPEAT; //tap detection
		//public volatile Boolean MULTI; // Same as (mKeySecondary != 0)
		public volatile Boolean INJECTED; //Key injected by this module
		//public volatile Boolean DISPATCH_ORIG_EVENT;  //long press delay dispatch is ongoing
		//public volatile Boolean HAS_TAP; //tap is enabled (set from configuration for primary key)
		//public volatile Boolean HAS_MULTI; //multi is enabled (static from configuration)
		//cache actions. null is same as "default"
		public String clickAction;
		public String tapAction;
		public String pressAction;
		//Time at first down
		public long originalDownTime;
		public int upCount;
		public long firstUpTime;
		
		public void reset() {
			//DOWN = false;
			ACTION_DONE = false;
			//RESET = false;
			//ONGOING = false;
			//LONGPRESS_DEFAULT_ACTION = false;
			//REPEAT = false;
			//MULTI = false;
			INJECTED = false;
			//DISPATCH_ORIG_EVENT = false;
			//HAS_TAP = false;
			//HAS_MULTI = false;
			clickAction = null;
			tapAction = null;
			pressAction = null;
			firstUpTime = 0;
		}
	}
	
	protected void resetEvent() {
		resetEventForSecondary();
		mKeyFlags.mKeyPrimary = 0;
		mKeyFlags.mKeySecondary = 0;
		mKeyFlags.upCount = 0;
		//mKeyFlags.firstUpTime = 0;
	}
	
	protected void resetEventForSecondary() {
		mKeyFlags.reset();
		mKeyFlags.mKeyRepeat = 0;
	}
	
	//Previously public volatile Boolean MULTI = false;
	private Boolean isMulti() {
		return (mKeyFlags.mKeySecondary != 0);
	}
	
	//Previously public volatile Boolean ONGOING = false;
	private Boolean isOnGoing() {
		return (mKeyFlags.mKeyPrimary != 0);
	}
	
	private static int tapDelay() { return Common.Remap.getTapDelay(); }
	private static int pressDelay() { return Common.Remap.getPressDelay(); }

	//Handler context
	private static Handler mHandler;
	private final KeyActionRunnable mMappingRunnable = new KeyActionRunnable();
	private final PostponedUpKeyRunnable mPostponedUpKeyRunnable = new PostponedUpKeyRunnable();
	
	//private static HapticFeedbackLw mHapticFeedbackLw;
	private ArrayList<Integer> mPendingKeys = new ArrayList<Integer>();

	class PostponedUpKeyRunnable implements Runnable {
		public void run() {
			//mPendingKeys is only accessed in mHandler
			if(mPendingKeys.size() > 0) {
				if(DEBUG) {
					String str = "Handler: Injecting delayed up key code(s): " + mPendingKeys.toString() + " for " + mKeyFlags.mKeyPrimary + " " + mKeyFlags.mKeySecondary;
					Common.log(TAG, str);
				}
				for (Integer keyCode: mPendingKeys) {
					if (keyCode > 0) {
						triggerKeyEvent(keyCode, 0, true);
					}
				}
				mPendingKeys.clear();
			}
		}
	}
	
	class KeyActionRunnable implements Runnable {
		public String keyAction;
		public Boolean immediateUp;
		
        @SuppressLint("NewApi")
		@Override
        public void run() {
        	if(keyAction == null) {
        		keyAction = "default";
        	}

    		if(mKeyFlags.ACTION_DONE) {
    			android.util.Log.i(TAG, "Handler: Race condition, other action already done: " + mKeyFlags.mKeyPrimary + " " + keyAction);
    			return;
    		}
        	//Cancel further processing
            mKeyFlags.ACTION_DONE = true;
        	
        	if ((mKeyFlags.mKeyRepeat > 0)/* || mKeyFlags.DOWN*/) {
        		performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        	}
        	
        	if (keyAction.equals("disabled")) {
        		if(DEBUG)Common.log(TAG, "Handler: Action is disabled, skipping");
        		
        		// Disable the button
        		
        	} else if (keyAction.equals("poweron")) { 
        		if(DEBUG)Common.log(TAG, "Handler: Invoking forced power on");

        		if (SDK_NUMBER >= 17) {
        			mPowerManager.wakeUp(SystemClock.uptimeMillis());
        		
        		} else {
        			aquireWakelock();
        			mPowerManager.userActivity(SystemClock.uptimeMillis(), false);
        		}
        		
        	} else if (keyAction.equals("poweroff")) { 
        		if(DEBUG)Common.log(TAG, "Handler: Invoking forced power off");
        		
        		/*
        		 * Passing the power code does not always work on Gingerbread.
        		 * So like poweron, we also make a poweroff to force the device off.
        		 */
        		mPowerManager.goToSleep(SystemClock.uptimeMillis());
        		
        	} else if (keyAction.equals("recentapps")) {
        		if(DEBUG)Common.log(TAG, "Handler: Invoking Recent Apps dialog");
        		
        		openRecentAppsDialog();
        		
        	} else if (keyAction.equals("previousapp")) {
        		if(DEBUG)Common.log(TAG, "Handler: Invoking Previous App");
        		
        		switchToLastApp();
        		
        	} else if (keyAction.equals("powermenu")) {
        		if(DEBUG)Common.log(TAG, "Handler: Invoking Power Menu dialog");
        		
        		openGlobalActionsDialog();
        		
        	} else if (keyAction.equals("flipleft")) {
        		if(DEBUG)Common.log(TAG, "Handler: Invoking left orientation");
        		
        		rotateLeft();
        		
        	} else if (keyAction.equals("flipright")) {
        		if(DEBUG)Common.log(TAG, "Handler: Invoking right orientation");
        		
        		rotateRight();
        		
        	} else if (keyAction.equals("fliptoggle")) {
        		if(DEBUG)Common.log(TAG, "Handler: Invoking orientation toggle");
        		
        		toggleRotation();
        		
        	} else if (keyAction.equals("killapp")) {
        		if(DEBUG)Common.log(TAG, "Handler: Invoking kill foreground application");
        		
        		killForegroundApplication();
        		
        	} else {
        		int[] keyArray;
            	if (keyAction.equals("default")) {
            		//TODO: only for multi keys?
            		keyArray = new int[2];
            		keyArray[0] = mKeyFlags.mKeyPrimary;
            		keyArray[1] = mKeyFlags.mKeySecondary;
            		//isKeyRepeat() will just trigger one key trigger
            	} else {
            		//Action was one or more key codes
        			String[] keyArray2 = keyAction.split(",");
            		keyArray = new int[keyArray2.length];
        			for (int i=0; i < keyArray2.length; i++) {
        				Integer keyCode = Integer.parseInt(keyArray2[i]);
        				keyArray[i] = keyCode;
        			}
            	}
            	
        		if(DEBUG){
        			String str = "Handler: Injecting key code(s) for " + mKeyFlags.mKeyPrimary + "/" + mKeyFlags.mKeySecondary + " " + immediateUp + ": "+java.util.Arrays.toString(keyArray);
        			Common.log(TAG, str);
        		}
        		mKeyFlags.INJECTED = true;
    			
				//immediateUp=true;
				for (Integer keyCode: keyArray) {
    				if (keyCode > 0) {
    					triggerKeyEvent(keyCode, mKeyFlags.originalDownTime, immediateUp);
    				}
            	}
				if (mPendingKeys.size() > 0) {
					String s = "Handler: Up not sent for all keys before next action: " + mPendingKeys.toString();
    			    Common.log(TAG, s);
   			    
				}
    			if(!immediateUp) {
    				//Caller waits for user to release a button before sending up
    				for (Integer keyCode: keyArray) {
    					if (!mPendingKeys.contains(keyCode)) {
    						mPendingKeys.add(keyCode);
    					}
    				}
    				//The delayed key up is triggered by any key press,
    				//but should latest be triggered as long press (pressDelay here must match system)
    				runPendingUpKeys(pressDelay()*5/4);
    			}
        	}
        }
	};
	
	protected final Runnable mReleasePartialWakelock = new Runnable() {
        @Override
        public void run() {
        	if (mWakeLockPartial.isHeld()) {
        		if(DEBUG)Common.log(TAG, "Releasing partial wakelock");
        		
        		mWakeLockPartial.release();
        	}
        }
	};
	
    protected final Runnable mReleaseWakelock = new Runnable() {
        @Override
        public void run() {
        	if (mWakeLock.isHeld()) {
        		if(DEBUG)Common.log(TAG, "Releasing wakelock");
        		
        		mWakeLock.release();
        	}
        }
    };
	
	private void hook_construct(final MethodHookParam param) {
		mHookedReference = new WeakReference<Object>(param.thisObject);
		
		mWindowManagerPolicyClass = XposedTools.findClass("android.view.WindowManagerPolicy");
		mActivityManagerNativeClass = XposedTools.findClass("android.app.ActivityManagerNative");
		mServiceManagerClass = XposedTools.findClass("android.os.ServiceManager");
		mProcessClass = XposedTools.findClass("android.os.Process");
		mWindowStateClass = XposedTools.findClass("android.view.WindowManagerPolicy$WindowState");
		
		if (SDK_NUMBER >= 16) {
			mInputManagerClass = XposedTools.findClass("android.hardware.input.InputManager");
			INJECT_INPUT_EVENT_MODE_ASYNC = (Integer) XposedTools.getField(mInputManagerClass, "INJECT_INPUT_EVENT_MODE_ASYNC");
		}

		FLAG_INJECTED = (Integer) XposedTools.getField(mWindowManagerPolicyClass, "FLAG_INJECTED");
		FLAG_VIRTUAL = (Integer) XposedTools.getField(mWindowManagerPolicyClass, "FLAG_VIRTUAL");
		ACTION_DISPATCH = (Integer) XposedTools.getField(mWindowManagerPolicyClass, "ACTION_PASS_TO_USER");
	}
	
	/**
	 * Gingerbread uses arguments init(Context, IWindowManager, LocalPowerManager)
	 * ICS uses arguments init(Context, IWindowManager, WindowManagerFuncs, LocalPowerManager)
	 * JellyBean uses arguments init(Context, IWindowManager, WindowManagerFuncs)
	 */
	private void hook_init(final MethodHookParam param) {
    	mContext = (Context) param.args[0];
    	mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
    	mWakeLock = mPowerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "PhoneWindowManagerHook");
    	mWakeLockPartial = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneWindowManagerHookPartial");
    	mHandler = new Handler();
    	mWindowManager = param.args[1];
    	//mHapticFeedbackLw = new HapticFeedbackLw(mContext);
    	
    	mContext.registerReceiver(
    		new BroadcastReceiver() {
	    		@Override
	    		public void onReceive(Context context, Intent intent) {
	    			if (intent.getStringExtra("request").equals(Common.BroadcastOptions.REQUEST_ENABLE_KEYCODE_INTERCEPT)) {
	    				//Send all keys to config dialog
	    				mInterceptKeycode = true;
	    				
	    			} else if (intent.getStringExtra("request").equals(Common.BroadcastOptions.REQUEST_DISABLE_KEYCODE_INTERCEPT)) {
	    				mInterceptKeycode = false;
	    				
	    			} else if (intent.getStringExtra("request").equals(Common.BroadcastOptions.REQUEST_RELOAD_CONFIGS)) {
	    				Common.loadSharedPreferences(null, true);
	    			}
	    		}
	    	}, 
	    	new IntentFilter(Common.BroadcastOptions.INTENT_ACTION_REQUEST), 
	    	Common.BroadcastOptions.PERMISSION_REQUEST, 
	    	null
	    );

    	mContext.registerReceiver(
    		new BroadcastReceiver() {
	    		@Override
	    		public void onReceive(Context context, Intent intent) {
	    			mBootCompleted = true;
	    			
	    			mContext.unregisterReceiver(this);
	    		}
	    	}, 
	    	new IntentFilter("android.intent.action.BOOT_COMPLETED"), 
	    	null, 
	    	null
    	);
	}

	private String getParam(int keyCode, Boolean down) {
			return ": " + keyCode + " " + mKeyFlags.mKeyPrimary + "/" + mKeyFlags.mKeySecondary + ": " + down + " " + mKeyFlags.ACTION_DONE + " " + mKeyFlags.mKeyRepeat;
    }
	
	/**
	 * Gingerbread uses arguments interceptKeyBeforeQueueing(Long whenNanos, Integer action, Integer flags, Integer keyCode, Integer scanCode, Integer policyFlags, Boolean isScreenOn)
	 * ICS/JellyBean uses arguments interceptKeyBeforeQueueing(KeyEvent event, Integer policyFlags, Boolean isScreenOn)
	 */
	private void hook_interceptKeyBeforeQueueing(final MethodHookParam param) {
		/*
		 * Do nothing until the device is done booting
		 */
		if (!mBootCompleted) {
			param.setResult(ACTION_DISABLE);
			
			return;
		}
		
		final int action = (Integer) (SDK_NUMBER <= 10 ? param.args[1] : ((KeyEvent) param.args[0]).getAction());
		final int policyFlags = (Integer) (SDK_NUMBER <= 10 ? param.args[5] : param.args[1]);
		final int keyCode = (Integer) (SDK_NUMBER <= 10 ? param.args[3] : ((KeyEvent) param.args[0]).getKeyCode());
		final boolean isScreenOn = (Boolean) (SDK_NUMBER <= 10 ? param.args[6] : param.args[2]);
		final boolean down = (action == KeyEvent.ACTION_DOWN);
		final long downTime = (long) (SDK_NUMBER <= 10 ? SystemClock.uptimeMillis() : ((KeyEvent) param.args[0]).getDownTime());
		final long eventTime = (long) (SDK_NUMBER <= 10 ? SystemClock.uptimeMillis() : ((KeyEvent) param.args[0]).getEventTime());
		
		boolean setup = false;
		
		/*
		 * Do not handle injected keys. 
		 * They might even be our own doing or could for other
		 * reasons create a loop or break something.
		 */
		if ((policyFlags & FLAG_INJECTED) != 0) {
			if(DEBUG)Common.log(TAG, "Queueing: Key code was injected, passing on" + getParam(keyCode, down));
			
			if (mKeyFlags.INJECTED) {
				param.args[ (SDK_NUMBER <= 10 ? 5 : 1) ] = (policyFlags ^ FLAG_INJECTED);
				
				if (!down) {
					mKeyFlags.INJECTED = false;
				}
			}
			
			return;
		}
		//Complete on any (non injected) key up, also new events
		runPendingUpKeys(0);
		
		/*
		 * During the first key down while the screen is off,
		 * we will reset everything to avoid issues if the screen was turned off by 
		 * a virtual key. Those keys does not execute key up when the screen is off. 
		 */
		if (down && ((!isScreenOn && isScreenOn != mKeyFlags.mWasScreenOn) /*|| mKeyFlags.RESET*/)) {
			if(DEBUG)Common.log(TAG, "Queueing: Re-setting old flags" + getParam(keyCode, down));
			
			resetEvent();
		}
		
		if (mInterceptKeycode && isScreenOn) {
			if (down) {
				if ((policyFlags & FLAG_VIRTUAL) != 0) {
					performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
				}
				
				sendBroadcastResponse(keyCode);
			}
			
			param.setResult(ACTION_DISABLE);
			
			return;
		}
		
		//Find if the key is out of sequence
		//mKeyFlags.DOWN = down;
		if (down) {
			//Down
			if (isOnGoing() && !mKeyFlags.ACTION_DONE && (keyCode == mKeyFlags.mKeyPrimary) && (mKeyFlags.mKeyRepeat == 0) && (mKeyFlags.tapAction != null)) {
				//Primary pressed again, increase repeat (so we can check the order)
				if(DEBUG)Common.log(TAG, "Queueing: Registering key repeat" + getParam(keyCode, down));
				
				if (!isMulti()) {
					mKeyFlags.mKeyRepeat++;
				}
				
			} else if (isOnGoing() && !mKeyFlags.ACTION_DONE && (keyCode == mKeyFlags.mKeySecondary) && (mKeyFlags.mKeyRepeat == 0) && (mKeyFlags.tapAction != null)) {
					//The secondary completes the sequence
					if(DEBUG)Common.log(TAG, "Queueing: Secondary key repeat" + getParam(keyCode, down));
					
					mKeyFlags.mKeyRepeat++;
					
			} else if (isOnGoing() && !mKeyFlags.ACTION_DONE && (keyCode != mKeyFlags.mKeyPrimary) && (mKeyFlags.mKeySecondary == 0) && (mKeyFlags.mKeyRepeat == 0)) {
				//First down of a secondary key (if no multi for this combination, the secondary is checked as primary)
				if(DEBUG)Common.log(TAG, "Queueing: Adding secondary key" + getParam(keyCode, down));
				
				resetEventForSecondary();
				setup = true;
				
				mKeyFlags.mKeySecondary = keyCode;
				
			} else {
				//No current event found, start a new
				if(DEBUG)Common.log(TAG, "Queueing: Starting new event" + getParam(keyCode, down)+mKeyFlags.tapAction);
				
				resetEvent();
				setup = true;
				
				mKeyFlags.mWasScreenOn = isScreenOn;
				mKeyFlags.originalDownTime = downTime;
				mKeyFlags.upCount = 0;

				mKeyFlags.mKeyPrimary = keyCode;
				mKeyFlags.mKeySecondary = 0;
			}
		} else {
			//up
			if(!isOnGoing()) {
  			    if(DEBUG)Common.log(TAG, "Queueing: The up key code is not ongoing. Returning it to the original handler" + getParam(keyCode, down));
			
			    return;
   		    }

			//Do not check that up multi key are in sequence, only down (ongoing)
			if ((keyCode == mKeyFlags.mKeyPrimary) || (keyCode == mKeyFlags.mKeySecondary)) {
				if(DEBUG)Common.log(TAG, "Queueing: Up" + getParam(keyCode, down)+ " " + mKeyFlags.upCount);
				if(mKeyFlags.upCount == 0) {
					mKeyFlags.firstUpTime = eventTime;
				}
				mKeyFlags.upCount++;
			} else {
				if(DEBUG)Common.log(TAG, "Queueing: The key up code is not ours. Disabling it as we have an ongoing event" + getParam(keyCode, down));
				
				//mKeyFlags.RESET = true;
				param.setResult(ACTION_DISABLE);
				
				return;
			}
		}
		
		//If delayed click or long press is waiting, stop them
		if (!mKeyFlags.ACTION_DONE) {
			//Do not stop ongoing 
			removeHandler();
		}
		
		//Check if there is any (possible) action for a new key (-combination)
		if (setup) {
			if(DEBUG)Common.log(TAG, "Queueing: New event setup" + getParam(keyCode, down));

			//preferences are related to a combined key code
			int combinedKeyCode = Common.generateKeyCode(mKeyFlags.mKeyPrimary, mKeyFlags.mKeySecondary);
						
			Boolean isKeyEnabled = Common.Remap.isKeyEnabled(combinedKeyCode, !isScreenOn);
			if (!isKeyEnabled && isMulti() && (mKeyFlags.mKeyPrimary != mKeyFlags.mKeySecondary) && Common.Remap.isMultiEnabled(mContext, mKeyFlags.mKeyPrimary)) {
				if(DEBUG)Common.log(TAG, "Queueing: The primary/secondary key has no actions. Converting secondary into a primary key and starting a new event" + getParam(keyCode, down));

				mKeyFlags.mKeyPrimary = mKeyFlags.mKeySecondary;
				mKeyFlags.mKeySecondary = 0;
				combinedKeyCode = Common.generateKeyCode(mKeyFlags.mKeyPrimary, mKeyFlags.mKeySecondary);
				isKeyEnabled = Common.Remap.isKeyEnabled(combinedKeyCode, !isScreenOn);				
			}

			if (!isKeyEnabled) {
				if(DEBUG)Common.log(TAG, "Queueing: key is not enabled, returning it to the original method" + getParam(keyCode, down));

				resetEvent();
				return;
			}
			
			//Get all the possible actions
			mKeyFlags.clickAction = Common.Remap.getKeyClick(combinedKeyCode, !isScreenOn);
		    if (mKeyFlags.clickAction != null && mKeyFlags.clickAction.equals("default")){
		    	mKeyFlags.clickAction = null;
		    }
			mKeyFlags.tapAction = Common.Remap.getKeyTap(mContext, combinedKeyCode, !isScreenOn);
			if (mKeyFlags.tapAction != null && mKeyFlags.tapAction.equals("default")){
				mKeyFlags.tapAction = null;
			}
			mKeyFlags.pressAction = Common.Remap.getKeyPress(combinedKeyCode, !isScreenOn);
		    if (mKeyFlags.pressAction != null && mKeyFlags.pressAction.equals("default")){
		    	mKeyFlags.pressAction = null;
		    }
		    //only default actions is OK if there are multi actions for first key
			if ((mKeyFlags.clickAction == null) && (mKeyFlags.pressAction == null) && (mKeyFlags.tapAction == null)) {
				if (isMulti() || (!isMulti() && !Common.Remap.isMultiEnabled(mContext, mKeyFlags.mKeyPrimary))) {
					if(DEBUG)Common.log(TAG, "Queueing: No action for an enabled key" + getParam(keyCode, down));

					resetEvent();
					return;
				}
			}
			
			if (!isScreenOn) {
				aquirePartialWakelock();
				
			} else {
				releasePartialWakelock();
			}
			
			releaseWakelock();

			if ((policyFlags & FLAG_VIRTUAL) != 0) {
				performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
			}
		}

		//Find the action for the event (disable/dispatch) as well as starting timers		
		//For up events, no primary/secondary check (sequence checked already)
		//Longpress is triggered from first down
		//Click is relayed when possible: Down is sent if there is no other event,
		//otherwise wait for up
		//This means that if an action is set as click, you must configure longpress ore double tap to not always act on click
		//The reason is that this allows for longpress passing from Click detection
		if (mKeyFlags.ACTION_DONE) {
			//Event already handled, no further invoking/dispatching
			if(DEBUG)Common.log(TAG, "Queueing: Action done for" + getParam(keyCode, down));
			param.setResult(ACTION_DISABLE);

		} else {
			if (down) {
				//Key down, is sequence completed?

				if((keyCode != mKeyFlags.mKeyPrimary) &&   !isMulti() ||
				   (keyCode != mKeyFlags.mKeySecondary) &&  isMulti()) {

					if(DEBUG)Common.log(TAG, "Queueing: Sequence incomplete" + getParam(keyCode, down));
					param.setResult(ACTION_DISABLE);
					
				} else {
					if (mKeyFlags.mKeyRepeat == 0) {
//TODO: Replace handling
//						if ((mKeyFlags.clickAction != null) && (mKeyFlags.pressAction == null) && (mKeyFlags.tapAction == null)) {
//							if(DEBUG)Common.log(TAG, "Queueing: Invoking click (no tap/press)" + getParam(keyCode, down));
//
//							mKeyFlags.ACTION_DONE = true;
//							param.setResult(ACTION_DISABLE);
//							invokeHandler(0, mKeyFlags.clickAction, down);
//						
//						} else {
						{
							{//if (mKeyFlags.pressAction != null) {
								//There is a long press event for the key
								if(DEBUG)Common.log(TAG, "Queueing: Invoking long press handler" + getParam(keyCode, down));

								//Note: This also affects "standard" actions like Back, Menu
								//Double long press gives long press action
								Boolean immediateUp = !down;
								invokeHandler(pressDelay(), mKeyFlags.pressAction, !down);
								param.setResult(ACTION_DISABLE);
							}
						}

					} else if (this.mKeyFlags.mKeyRepeat == 1) {
						//Second down
						//Default means no detection, i.e. only the second click is handled
						if (mKeyFlags.tapAction != null) {
							if(DEBUG)Common.log(TAG, "Queueing: Invoking tap" + getParam(keyCode, down));

							//Note: This also affects "standard" actions like Back, Menu
							//long press gives long press action
							Boolean immediateUp = !down;
							invokeHandler(0, mKeyFlags.tapAction, immediateUp);
							param.setResult(ACTION_DISABLE);
						} else {
			    			android.util.Log.i(TAG, "Queueing: Unexpected no tap action" + getParam(keyCode, down));
						}
					} else {
		    			android.util.Log.i(TAG, "Queueing: Unexpected repeat" + getParam(keyCode, down));
					}
				}
			} else {
				//All actions on Up cannot handle delays, so long press cannot be relayed

				if (mKeyFlags.mKeyRepeat == 0) {
					//First up, nothing done for the event: This is a click or tap
					//For multi events this may run at first up and set ACTION_DONE or the handler is cancelled and triggered again
					int keyTapDelay = 0;
					if (mKeyFlags.tapAction != null) {
						//"Normal" is 1/3 of standard Android time. 
						//TODO: Add system configuration or increase defaults?
					    keyTapDelay = 3*tapDelay();
					    if (isMulti()) {
					    	keyTapDelay*= 3/2;
					    }
					    keyTapDelay -= (int)(SystemClock.uptimeMillis() - mKeyFlags.firstUpTime); 
					}
					if (keyTapDelay < 0) {
						keyTapDelay = 0;
				    }

					if(DEBUG)Common.log(TAG, "Queueing: Invoking click/tap handler ("+keyTapDelay+")" + getParam(keyCode, down));
					//For multi we delay if this is the first up (so it is possible to get click and longpress for click)
					Boolean immediateUp = !down && (!isMulti() || isMulti() && (mKeyFlags.upCount > 0));
					invokeHandler(keyTapDelay, mKeyFlags.clickAction, immediateUp );
				}
				
				//No dispatching for ongoing up events
                param.setResult(ACTION_DISABLE);
			}
		}
	}

	/**
	 * Gingerbread uses arguments interceptKeyBeforeDispatching(WindowState win, Integer action, Integer flags, Integer keyCode, Integer scanCode, Integer metaState, Integer repeatCount, Integer policyFlags)
	 * ICS/JellyBean uses arguments interceptKeyBeforeDispatching(WindowState win, KeyEvent event, Integer policyFlags)
	 */
	private void hook_interceptKeyBeforeDispatching(final MethodHookParam param) {
		final int keyCode = (Integer) (SDK_NUMBER <= 10 ? param.args[3] : ((KeyEvent) param.args[1]).getKeyCode());
		final int action = (Integer) (SDK_NUMBER <= 10 ? param.args[1] : ((KeyEvent) param.args[1]).getAction());
		final int repeatCount = (Integer) (SDK_NUMBER <= 10 ? param.args[6] : ((KeyEvent) param.args[1]).getRepeatCount());
		final int policyFlags = (Integer) (SDK_NUMBER <= 10 ? param.args[7] : param.args[2]);
		final boolean down = action == KeyEvent.ACTION_DOWN;
		
		if ((policyFlags & FLAG_INJECTED) != 0) {
			if(DEBUG)Common.log(TAG, "Dispatching: Key code was injected, passing on" + getParam(keyCode, down));
			if (mKeyFlags.INJECTED) {
				param.args[ (SDK_NUMBER <= 10 ? 7 : 2) ] = (policyFlags ^ FLAG_INJECTED);
			}
			
			return;
		}
		
		if (isOnGoing()) {
			if(DEBUG)Common.log(TAG, "Dispatching xxx" + getParam(keyCode, down));
			param.setResult(ACTION_DISPATCH_DISABLED);

 			return;
			
		} else {
			if(DEBUG && down)Common.log(TAG, "Dispatching xxx: The key code is not ongoing, passing on" + getParam(keyCode, down));
		}
	}

	protected void sendBroadcastResponse(Integer value) {
		Intent intent = new Intent(Common.BroadcastOptions.INTENT_ACTION_RESPONSE);
		intent.putExtra("response", value);
		
		mContext.sendBroadcast(intent);
	}
	
	protected void runPendingUpKeys(Integer timeout) {
		if(DEBUG)Common.log(TAG, "Run pending key up: " + mPendingKeys.toString());
		if (timeout > 0) {
			mHandler.postDelayed(mPostponedUpKeyRunnable, timeout);
			
		} else {
			mHandler.post(mPostponedUpKeyRunnable);
		}
	}
	
	protected void removeHandler() {
		if (isOnGoing()) {
			if(DEBUG)Common.log(TAG, "Removing any existing pending handlers");
			
			mHandler.removeCallbacks(mMappingRunnable);
		}
	}
	
	protected void invokeHandler(Integer timeout, String action, Boolean immediateUp) {
		mMappingRunnable.keyAction = action;
		mMappingRunnable.immediateUp = immediateUp;
		if (timeout > 0) {
			mHandler.postDelayed(mMappingRunnable, timeout);
			
		} else {
			mHandler.post(mMappingRunnable);
		}
	}
	
	protected void releaseWakelock() {
		if (mWakeLock.isHeld()) {
			if(DEBUG)Common.log(TAG, "Releasing wakelock");
			
			mHandler.removeCallbacks(mReleaseWakelock);
			mWakeLock.release();
		}
	}
	
	protected void releasePartialWakelock() {
		if (mWakeLockPartial.isHeld()) {
			if(DEBUG)Common.log(TAG, "Releasing partial wakelock");
			
			mHandler.removeCallbacks(mReleasePartialWakelock);
			mWakeLockPartial.release();
		}
	}
	
	public void aquireWakelock() {
		if (mWakeLock.isHeld()) {
			mHandler.removeCallbacks(mReleaseWakelock);
			
		} else {
			mWakeLock.acquire();
		}
		
		if(DEBUG)Common.log(TAG, "Aquiring new wakelock");
		
		mHandler.postDelayed(mReleaseWakelock, 10000);
	}
	
	public void aquirePartialWakelock() {
		if (mWakeLockPartial.isHeld()) {
			mHandler.removeCallbacks(mReleasePartialWakelock);
			
		} else {
			mWakeLockPartial.acquire();
		}
		
		if(DEBUG)Common.log(TAG, "Aquiring new partial wakelock");
		
		mHandler.postDelayed(mReleasePartialWakelock, 3000);
	}

	private void openRecentAppsDialog() {
		sendCloseSystemWindows("recentapps");
		
		if (SDK_NUMBER > 10) {
			if (mRecentAppsTrigger == null) {
				mRecentAppsTrigger = IStatusBarService.Stub.asInterface(
					(IBinder) XposedTools.callMethod(
						mServiceManagerClass,
						"getService",
						new Class<?>[]{String.class},
						"statusbar"
					)
				);
			}
			
			XposedTools.callMethod(mRecentAppsTrigger, "toggleRecentApps");
			
		} else {
			if (mRecentAppsTrigger == null) {
				mRecentAppsTrigger = XposedTools.callConstructor(
						XposedTools.findClass("com.android.internal.policy.impl.RecentApplicationsDialog"),
						new Class<?>[]{Context.class},
						mContext
				);
			}
			
			try {
				XposedTools.callMethod(mRecentAppsTrigger, "show");
				
			} catch (Throwable e) { e.printStackTrace(); }
		}
	}

    private void openGlobalActionsDialog() {
		XposedTools.callMethod(mHookedReference.get(), "showGlobalActionsDialog");
	}
	
	private Method xPerformHapticFeedback;
	private void performHapticFeedback(Integer effectId) {
        try {
            if (xPerformHapticFeedback == null) {
                    xPerformHapticFeedback = XposedTools.findMethod(mHookedReference.get().getClass(), "performHapticFeedbackLw", mWindowStateClass, Integer.TYPE, Boolean.TYPE);
            }
            
            xPerformHapticFeedback.invoke(mHookedReference.get(), null, effectId, false);

        } catch (Throwable e) { e.printStackTrace(); }
	}
	
	private void sendCloseSystemWindows(String reason) {
		if ((Boolean) XposedTools.callMethod(mActivityManagerNativeClass, "isSystemReady")) {
			XposedTools.callMethod(
					XposedTools.callMethod(mActivityManagerNativeClass, "getDefault"), 
					"closeSystemDialogs", 
					new Class<?>[]{String.class}, 
					reason
			);
		}
    }
	
	Method xInputEvent;
	Method xInputManager;
	@SuppressLint("InlinedApi")
	private void triggerKeyEvent(final int keyCode, long timeDown, Boolean up) {
		try {
			if (xInputEvent == null) {
				if (SDK_NUMBER >= 16) {
					xInputManager = XposedTools.findMethod(mInputManagerClass, "getInstance");
					xInputEvent = XposedTools.findMethod(mInputManagerClass, "injectInputEvent", KeyEvent.class, Integer.TYPE);
					
				} else {
					xInputEvent = XposedTools.findMethod(mWindowManager.getClass(), "injectInputEventNoWait", KeyEvent.class);
				}
			}
			
			KeyEvent downEvent = null;
			KeyEvent upEvent = null;
			
			if(timeDown > 0)
			{
				if (SDK_NUMBER >= 10) {
					long now = SystemClock.uptimeMillis();

					downEvent = new KeyEvent(timeDown, now, KeyEvent.ACTION_DOWN,
							keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
							KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);

				} else {
					downEvent = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
				}
			}
			if(up) {
				if (SDK_NUMBER >= 10) {
					long now = SystemClock.uptimeMillis();

					upEvent = new KeyEvent(now, now, KeyEvent.ACTION_UP,
							keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
							KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);

				} else {
					upEvent = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
				}
			}
			
			if (SDK_NUMBER >= 16) {
				Object inputManager = xInputManager.invoke(null);
				
				if (downEvent != null)xInputEvent.invoke(inputManager, downEvent, INJECT_INPUT_EVENT_MODE_ASYNC);
				if (upEvent != null)xInputEvent.invoke(inputManager, upEvent, INJECT_INPUT_EVENT_MODE_ASYNC);
				
			} else {
				if (downEvent != null)xInputEvent.invoke(mWindowManager, downEvent);
				if (upEvent != null)xInputEvent.invoke(mWindowManager, upEvent);
			}
			
		} catch (Throwable e) { e.printStackTrace(); }
	}
	
	private Boolean isRotationLocked() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION, 0) == 0;
	}
	
	Method xGetRotation;
	private Integer getRotation() {
		try {
			if (xGetRotation == null) {
				xGetRotation = XposedTools.findMethod(mWindowManager.getClass(), "getRotation");
			}
			
			return (Integer) xGetRotation.invoke(mWindowManager);
			
		} catch (Throwable e) { e.printStackTrace(); }
		
		return 0;
	}
	
	Method xRreezeRotation;
	private void freezeRotation(Integer orientation) {
		if (SDK_NUMBER > 10) {
			try {
				if (xRreezeRotation == null) {
					xRreezeRotation = XposedTools.findMethod(mWindowManager.getClass(), "freezeRotation", Integer.TYPE);
				}
				
				xRreezeRotation.invoke(mWindowManager, orientation);
				
			} catch (Throwable e) { e.printStackTrace(); }
			
		} else {
			/*
			 * TODO: Find a working way for locking Gingerbread in a specific orientation
			 */
			
			/* if (orientation < 0) {
				orientation = getRotation();
			} */
			
			Settings.System.putInt(mContext.getContentResolver(),
					Settings.System.ACCELEROMETER_ROTATION, 0);
			
			/* XposedHelpers.callMethod(
					mWindowManager, 
					"setRotationUnchecked",
					new Class<?>[]{Integer.TYPE, Boolean.TYPE, Integer.TYPE},
					orientation, false, 0
			); */
		}
	}
	
	Method xThawRotation;
	private void thawRotation() {
		if (SDK_NUMBER > 10) {
			try {
				if (xThawRotation == null) {
					xThawRotation = XposedTools.findMethod(mWindowManager.getClass(), "thawRotation");
				}
				
				xThawRotation.invoke(mWindowManager);
				
			} catch (Throwable e) { e.printStackTrace(); }
			
		} else {
			Settings.System.putInt(mContext.getContentResolver(), 
					Settings.System.ACCELEROMETER_ROTATION, 1);
		}
	}
	
	private Integer getNextRotation(Integer position) {
		Integer current=0, surface, next;
		Integer[] positions = new Integer[]{
			Surface.ROTATION_0,
			Surface.ROTATION_90,
			Surface.ROTATION_180,
			Surface.ROTATION_270
		};

		surface = getRotation();
		
		for (int i=0; i < positions.length; i++) {
			if ((int) positions[i] == (int) surface) {
				current = i + position; break;
			}
		}
		
		next = current >= positions.length ? 0 : 
				(current < 0 ? positions.length-1 : current);
		
		return positions[next];
	}
	
	private void rotateRight() {
		freezeRotation( getNextRotation(1) );
	}
	
	private void rotateLeft() {
		freezeRotation( getNextRotation(-1) );
	}
	
	private void toggleRotation() {
		if (isRotationLocked()) {
			thawRotation();
			Toast.makeText(mContext, "Rotation has been Enabled", Toast.LENGTH_SHORT).show();
			
		} else {
			freezeRotation(-1);
			Toast.makeText(mContext, "Rotation has been Disabled", Toast.LENGTH_SHORT).show();
		}
	}
	
	//From GravityBox
	@SuppressLint("NewApi")
	private void switchToLastApp() {
		int lastAppId = 0;
		int looper = 1;
		String packageName;
		final Intent intent = new Intent(Intent.ACTION_MAIN);
		final ActivityManager am = (ActivityManager) mContext
				.getSystemService(Context.ACTIVITY_SERVICE);
		String defaultHomePackage = "com.android.launcher";
		intent.addCategory(Intent.CATEGORY_HOME);
		final ResolveInfo res = mContext.getPackageManager().resolveActivity(intent, 0);
		if (res.activityInfo != null && !res.activityInfo.packageName.equals("android")) {
			defaultHomePackage = res.activityInfo.packageName;
		}
		List <ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(5);
		// lets get enough tasks to find something to switch to
		// Note, we'll only get as many as the system currently has - up to 5
		while ((lastAppId == 0) && (looper < tasks.size())) {
			packageName = tasks.get(looper).topActivity.getPackageName();
			if (!packageName.equals(defaultHomePackage) && !packageName.equals("com.android.systemui")) {
				lastAppId = tasks.get(looper).id;
			}
			looper++;
		}
		if (lastAppId != 0) {
			//SDK v 12 for MOVE_TASK_NO_USER_ACTION: Conditional check
			am.moveTaskToFront(lastAppId, ActivityManager.MOVE_TASK_NO_USER_ACTION);
		} else {
			Toast.makeText(mContext, "No previous app", Toast.LENGTH_SHORT).show();
		}
	}

	private void killForegroundApplication() {
		try {
	        final Intent intent = new Intent(Intent.ACTION_MAIN);
	        intent.addCategory(Intent.CATEGORY_HOME);
	        
	        final ResolveInfo res = mContext.getPackageManager().resolveActivity(intent, 0);
	        final String defaultHomePackage = res.activityInfo != null && !res.activityInfo.packageName.equals("android") ? 
	        		res.activityInfo.packageName : 
	        			 "com.android.launcher";
	        
	        Object activityManager = XposedTools.callMethod(mActivityManagerNativeClass, "getDefault");
	        List<RunningAppProcessInfo> apps = (List<RunningAppProcessInfo>) XposedTools.callMethod(activityManager, "getRunningAppProcesses");
	        int firstAppUid = (Integer) XposedTools.getField(mProcessClass, "FIRST_APPLICATION_UID");
	        int lastAppUid = (Integer) XposedTools.getField(mProcessClass, "LAST_APPLICATION_UID");
	        
	        for (RunningAppProcessInfo appInfo : apps) {
	        	int uid = appInfo.uid;
	        	
	        	if (uid >= firstAppUid && uid <= lastAppUid
	                    && appInfo.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
	        		
	        		if (appInfo.pkgList != null && (appInfo.pkgList.length > 0)) {
	        			for (String pkg : appInfo.pkgList) {
	        				if (!pkg.equals("com.android.systemui") && !pkg.equals(defaultHomePackage)) {
	        					XposedTools.callMethod_UserCurrent(activityManager, "forceStopPackage", pkg);
	        				}
	        			}
	        		} else {
	        			XposedTools.callMethod(mProcessClass, "killProcess", appInfo.pid);
	        		}
	        	}
	        }
	        
		} catch (Throwable e) { e.printStackTrace(); }
	}
}
