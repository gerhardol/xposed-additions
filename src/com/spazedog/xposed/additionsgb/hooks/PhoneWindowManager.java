package com.spazedog.xposed.additionsgb.hooks;

import java.lang.ref.WeakReference;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.content.ActivityNotFoundException;
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
import android.view.ViewConfiguration;
import android.widget.Toast;

import com.android.internal.statusbar.IStatusBarService;
import com.spazedog.xposed.additionsgb.Common;
import com.spazedog.xposed.additionsgb.tools.XposedTools;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

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
	
	private final int ACTION_DISABLE = 0;
	private final Object ACTION_DISPATCH_DISABLED = -1;
	
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
		protected static final int maxTapActions = 3;
		
		public volatile Boolean ACTION_DONE; //Wait for complete events and pending up keys
		public volatile Boolean INJECTED; //Key injected by this event
		//cache actions. null is same as "default"
		public String tapAction[] = new String[maxTapActions];
		public String pressAction[] = new String[maxTapActions];
		
		public long originalDownTime; //Time at first down
		public int upCount;
		public long upTime;
		
		public void reset() {
			ACTION_DONE = false;
			INJECTED = false;
			for (int i = 0; i < maxTapActions; i++) {
				tapAction[i] = null;
				pressAction[i] = null;
			}
			upTime = 0;
		}
	}
	
	protected void resetEvent() {
		resetEventForSecondary();
		mKeyFlags.mKeyPrimary = 0;
		mKeyFlags.mKeySecondary = 0;
		mKeyFlags.upCount = 0;
		//mKeyFlags.upTime = 0;
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
	
	//Any more action for the key combinations?
	private Boolean isMoreAction() {
		boolean moreAction = false;
		for(int i = mKeyFlags.mKeyRepeat; i < Flags.maxTapActions; i++) {
			moreAction = (mKeyFlags.tapAction[i] != null) || (mKeyFlags.pressAction[i] != null);
		}
		return moreAction;
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
						triggerKeyEvent(keyCode, -1, true, false);
					}
				}
				mPendingKeys.clear();
			}
		}
	}
	
	class KeyActionRunnable implements Runnable {
		public String keyAction;
		public Boolean downIsNow;
		public Boolean immediateUp;
		public Boolean longPress;
		
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
        	
        	if ((mKeyFlags.mKeyRepeat > 0)) {
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
//        	} else if (keyAction.equals("4")) {
//        		if(DEBUG)Common.log(TAG, "Handler: Invoking back");
//TODO: Direct back invoke, for devices that ignore Back key (Omate TrueSmart)        		
//        		onBackPressed();
        	} else if (keyAction.startsWith("application:")) {
        		if(DEBUG)Common.log(TAG, "Handler: Starting application");
        		
        		int i = keyAction.indexOf(':',("application:").length());
        		String s = "";
        		if (i>= 0) {
        			s=keyAction.substring(i);
        		}
        		//Error handling in runCustomApp
        		runCustomApp(s);
        		        		
        	} else {
        		int[] keyArray;
            	if (keyAction.equals("default")) {
            		keyArray = new int[2];
            		keyArray[0] = mKeyFlags.mKeyPrimary;
            		keyArray[1] = mKeyFlags.mKeySecondary;
            	} else {
            		if (keyAction.startsWith("keycode:")) {
                		int i = keyAction.indexOf(':',("keycode:").length());
                		if (i>= 0) {
                			keyAction=keyAction.substring(i);
                		} else {
			    			android.util.Log.i(TAG, "Handler: Unexpected no keyAction" + keyAction);
                			keyAction="";
                		}
                	}
            		//Action was one or more key codes
        			String[] keyArray2 = keyAction.split(",");
            		keyArray = new int[keyArray2.length];
        			for (int i=0; i < keyArray2.length; i++) {
        				Integer keyCode = Integer.parseInt(keyArray2[i]);
        				keyArray[i] = keyCode;
        			}
            	}
            	
        		if(DEBUG){
        			String str = "Handler: Injecting key code(s) for " + mKeyFlags.mKeyPrimary + "/" + mKeyFlags.mKeySecondary + " " + immediateUp + "/" + downIsNow + longPress+": "+java.util.Arrays.toString(keyArray);
        			Common.log(TAG, str);
        		}
        		mKeyFlags.INJECTED = true;
    			
				for (Integer keyCode: keyArray) {
    				if (keyCode > 0) {
    					Long dTime = (long)0;
    					if(!downIsNow) {
    						dTime = mKeyFlags.originalDownTime;
    					}
    					triggerKeyEvent(keyCode, dTime, immediateUp, longPress && (keyAction == "default"));
    	    			if(!immediateUp && !mPendingKeys.contains(keyCode)) {
    	    				//Caller waits for user to release a button before sending up
    	    				mPendingKeys.add(keyCode);
    	    			}
    				}
            	}
				if (mPendingKeys.size() > 0) {
					String s = "Handler: Up not sent for all keys before next action: " + mPendingKeys.toString();
    			    Common.log(TAG, s);
   			    
				}
    			if(!immediateUp) {
    				//There must be a delayed key up, triggered by any key press,
    				//safety triggered as long press (not pressDelay() here, must match system)
    				runPendingUpKeys(ViewConfiguration.getLongPressTimeout()*11/10);
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
		//ACTION_DISPATCH = (Integer) XposedTools.getField(mWindowManagerPolicyClass, "ACTION_PASS_TO_USER");
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
		
		final int action = (Integer) ((KeyEvent) param.args[0]).getAction();
		final int policyFlags = (Integer) (param.args[1]);
		final int keyCode = (Integer) ((KeyEvent) param.args[0]).getKeyCode();
		final boolean isScreenOn = (Boolean) (param.args[2]);
		final boolean down = (action == KeyEvent.ACTION_DOWN);
		final long downTime = (long) ((KeyEvent) param.args[0]).getDownTime();
		final long eventTime = (long) ((KeyEvent) param.args[0]).getEventTime();
		
		boolean setup = false;
		
		/*
		 * Do not handle injected keys. 
		 * They might even be our own doing or could for other
		 * reasons create a loop or break something.
		 */
		if ((policyFlags & FLAG_INJECTED) != 0) {
			if(DEBUG)Common.log(TAG, "Queueing: Key code was injected, passing on" + getParam(keyCode, down));
			
			if (mKeyFlags.INJECTED) {
				param.args[1] = (policyFlags ^ FLAG_INJECTED);
				
				if (!down) {
					mKeyFlags.INJECTED = false;
				}
			}
			
			return;
		}
		
		//intercept when configuring
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

		//Complete on any (non injected) key up, also new events
		runPendingUpKeys(0);
		
		/*
		 * During the first key down while the screen is off,
		 * we will reset everything to avoid issues if the screen was turned off by 
		 * a virtual key. Those keys does not execute key up when the screen is off. 
		 */
		if (down && ((!isScreenOn && isScreenOn != mKeyFlags.mWasScreenOn))) {
			if(DEBUG)Common.log(TAG, "Queueing: Re-setting old flags" + getParam(keyCode, down));
			
			resetEvent();
		}
		
		//Find if the key is out of sequence
		if (down) {
			//Down
			boolean keyInCurrentEvent = false;
			if (!isMoreAction()) {
				if(DEBUG)Common.log(TAG, "Queueing: No more action, starting new event" + getParam(keyCode, down)+mKeyFlags.tapAction[1]);

				//No action			
			} else if (isOnGoing() && !mKeyFlags.ACTION_DONE) {
				//Seem to be an ongoing event, check that it continues

				if (keyCode == mKeyFlags.mKeyPrimary) {

					//Primary pressed again
					if(DEBUG)Common.log(TAG, "Queueing: Registering key repeat" + getParam(keyCode, down));

					keyInCurrentEvent = true;
					if (!isMulti()) {
						//Single key, sequence completed
						mKeyFlags.mKeyRepeat++;
						mKeyFlags.originalDownTime = downTime;
					}
					mKeyFlags.upCount = 0;

				} else if (keyCode == mKeyFlags.mKeySecondary) {
					//The secondary completes the sequence
					if(DEBUG)Common.log(TAG, "Queueing: Secondary key repeat" + getParam(keyCode, down));

					keyInCurrentEvent = true;
					mKeyFlags.mKeyRepeat++;
					mKeyFlags.originalDownTime = downTime;				

				} else if ((keyCode != mKeyFlags.mKeyPrimary) && (mKeyFlags.mKeySecondary == 0) && (mKeyFlags.mKeyRepeat == 0)) {
					//First down of a secondary key (if no multi for this combination, the secondary is checked as primary)
					if(DEBUG)Common.log(TAG, "Queueing: Adding secondary key" + getParam(keyCode, down));

					resetEventForSecondary();
					setup = true;

					keyInCurrentEvent = true;
					mKeyFlags.upCount = 0;
					mKeyFlags.mKeySecondary = keyCode;

				} 
			}
			
			if (!keyInCurrentEvent) {
				//No current event found, start a new
				if(DEBUG)Common.log(TAG, "Queueing: Starting new event" + getParam(keyCode, down)+mKeyFlags.tapAction[1]);
				
				resetEvent();//current event
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
  			    if(DEBUG)Common.log(TAG, "Queueing: No ongoing event. Returning key to the original handler" + getParam(keyCode, down));
			
			    return;
   		    }

			//Do not check that up multi key are in sequence, only down (ongoing)
			if ((keyCode == mKeyFlags.mKeyPrimary) || (keyCode == mKeyFlags.mKeySecondary)) {
				if(DEBUG)Common.log(TAG, "Queueing: Up" + getParam(keyCode, down)+ " " + mKeyFlags.upCount);
				mKeyFlags.upCount++;
				if (isMulti() && mKeyFlags.upCount < 2){
					param.setResult(ACTION_DISABLE);
					return;
				}
			} else {
				if(DEBUG)Common.log(TAG, "Queueing: The key up code is not ours. Disabling it as we have an ongoing event" + getParam(keyCode, down));
				
				param.setResult(ACTION_DISABLE);
				return;
			}
		}
		
		//If delayed tap or long press is waiting, stop them
		if (!setup && !mKeyFlags.ACTION_DONE) {
			//TODO Review that ongoing are not removed 
			removeHandler();
		}
		
		//Check if there is any (possible) action for a new key (combination)
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
			boolean isAction = false;
			for (int i = 0; i < Flags.maxTapActions; i++) {
				mKeyFlags.tapAction[i] = Common.Remap.getKeyTap(mContext, combinedKeyCode, !isScreenOn, i);
				if (mKeyFlags.tapAction[i] != null && mKeyFlags.tapAction[i].equals("default")){
					mKeyFlags.tapAction[i] = null;
				}
				else {
					isAction = true;
				}
				
				mKeyFlags.pressAction[i] = Common.Remap.getKeyPress(mContext, combinedKeyCode, !isScreenOn, i);
				if (mKeyFlags.pressAction[i] != null && mKeyFlags.pressAction[i].equals("default")){
					mKeyFlags.pressAction[i] = null;
				}
				else {
					isAction = true;
				}
		    }
		    //only default actions is OK if there are multi actions for first key
			if (!isAction) {
				if (isMulti() || (!isMulti() && !Common.Remap.isMultiEnabled(mContext, mKeyFlags.mKeyPrimary))) {
					if(DEBUG)Common.log(TAG, "Queueing: No action for an enabled key, returning" + getParam(keyCode, down));

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

			mKeyFlags.upCount = 0;
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
			if(DEBUG)Common.log(TAG, "Queueing: Action already done for" + getParam(keyCode, down));
			param.setResult(ACTION_DISABLE);
			return;

		} else if(mKeyFlags.mKeyRepeat >= Flags.maxTapActions) {
			android.util.Log.i(TAG, "Queueing: Unexpected repeat" + getParam(keyCode, down));
			
		} else {
			if (down) {
				//Key down, is (multi) sequence completed?

				if((keyCode == mKeyFlags.mKeyPrimary)   && !isMulti() ||
				   (keyCode == mKeyFlags.mKeySecondary) &&  isMulti()) {
					
					//Possible long press event for the key
					if(DEBUG)Common.log(TAG, "Queueing: Invoking long press handler" + getParam(keyCode, down));

					//TODO: Double long press gives long press action
					Boolean immediateUp = !down;
					invokeHandler(pressDelay(), mKeyFlags.pressAction[mKeyFlags.mKeyRepeat], false, false, true);
					param.setResult(ACTION_DISABLE);
					return;
				} else {

					if(DEBUG)Common.log(TAG, "Queueing: Sequence incomplete" + getParam(keyCode, down));
					param.setResult(ACTION_DISABLE);
					return;
				}
			} else {
				//All actions on Up cannot handle delays, so long press cannot be relayed

				//First up (sequence), nothing done for the event: This is a click or tap
				int keyTapDelay = 0;
				boolean moreActions = isMoreAction();
				if (moreActions) {
					//"Normal" is 1/3 of standard Android time. 
					//TODO: Add system configuration or increase defaults?
					keyTapDelay = 3*tapDelay();
					if (isMulti()) {
						keyTapDelay *= 3/2;
					}
					//TODO: should the timeout be decresed since last down?
					//keyTapDelay -= (int)(SystemClock.uptimeMillis() - mKeyFlags.upTime); 
					if (keyTapDelay < 0) {
						keyTapDelay = 0;
					}
				}
				if(DEBUG)Common.log(TAG, "Queueing: Invoking click/tap handler ("+keyTapDelay+","+moreActions+")" + getParam(keyCode, down));
				//For multi we delay if this is the first up (so it is possible to get click and longpress for click)
				Boolean immediateUp = !moreActions;
				invokeHandler(keyTapDelay, mKeyFlags.tapAction[mKeyFlags.mKeyRepeat], true, immediateUp, false );

				//Save time for next tap
				mKeyFlags.upTime = eventTime;

				//No dispatching for ongoing up events
                param.setResult(ACTION_DISABLE);
    			return;
			}
		}
	}

	//This hook should no longer be needed, should be removed
	/**
	 * Gingerbread uses arguments interceptKeyBeforeDispatching(WindowState win, Integer action, Integer flags, Integer keyCode, Integer scanCode, Integer metaState, Integer repeatCount, Integer policyFlags)
	 * ICS/JellyBean uses arguments interceptKeyBeforeDispatching(WindowState win, KeyEvent event, Integer policyFlags)
	 */
	private void hook_interceptKeyBeforeDispatching(final MethodHookParam param) {
		final int keyCode = (Integer) ((KeyEvent) param.args[1]).getKeyCode();
		final int action = (Integer) ((KeyEvent) param.args[1]).getAction();
		//final int repeatCount = (Integer) ((KeyEvent) param.args[1]).getRepeatCount();
		final int policyFlags = (Integer) (param.args[2]);
		final boolean down = action == KeyEvent.ACTION_DOWN;
		
		String str ="";
		//Temporary
		for (Object o: param.args){
			str+=" "+o;
		}
		if ((policyFlags & FLAG_INJECTED) != 0) {
			if(DEBUG)Common.log(TAG, "Dispatching: Key code was injected, dispatching" + getParam(keyCode, down)+str);
			if (mKeyFlags.INJECTED) {
				param.args[2] = (policyFlags ^ FLAG_INJECTED);
			}
			
			return;
		}
		
		if (isOnGoing()) {
			//Occurs for long press, dispatching without queuing
			android.util.Log.i(TAG, "Dispatching: Ongoing, no dispatching." + getParam(keyCode, down)+str);
			param.setResult(ACTION_DISPATCH_DISABLED);
			return;
		} else {
			
			android.util.Log.i(TAG, "Dispatching: Not ongoing, dispatching." + getParam(keyCode, down)+str);
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
	
	protected void invokeHandler(Integer timeout, String action, Boolean downTimeIsNow, Boolean immediateUp, Boolean defaultIsLongPress) {
		mMappingRunnable.keyAction = action;
		mMappingRunnable.downIsNow = downTimeIsNow;
		mMappingRunnable.immediateUp = immediateUp;
		mMappingRunnable.longPress = defaultIsLongPress;

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
	private void triggerKeyEvent(final int keyCode, long timeDown, Boolean up, Boolean longPress) {
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
			KeyEvent downEvent2 = null;
			KeyEvent upEvent = null;
			long now = SystemClock.uptimeMillis();
			
			//Temp workaround
			if(timeDown >=0)
			{
				up = true;
			if(timeDown == 0) {
				timeDown = now;
			}
			if(timeDown > 0)
			{
				downEvent = new KeyEvent(timeDown, now, KeyEvent.ACTION_DOWN,
						keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
						KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
				if(longPress){
					downEvent2 = new KeyEvent(timeDown, now, KeyEvent.ACTION_DOWN,
							keyCode, 1, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
							KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_LONG_PRESS, InputDevice.SOURCE_KEYBOARD);
					
				}
			}
			if(up) {
				upEvent = new KeyEvent(now, now, KeyEvent.ACTION_UP,
						keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
						KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
			}
			}
			
			if (SDK_NUMBER >= 16) {
				Object inputManager = xInputManager.invoke(null);
				
				if (downEvent != null)xInputEvent.invoke(inputManager, downEvent, INJECT_INPUT_EVENT_MODE_ASYNC);
				if (downEvent2 != null)xInputEvent.invoke(inputManager, downEvent2, INJECT_INPUT_EVENT_MODE_ASYNC);
				if (upEvent != null)xInputEvent.invoke(inputManager, upEvent, INJECT_INPUT_EVENT_MODE_ASYNC);
				
			} else {
				if (downEvent != null)xInputEvent.invoke(mWindowManager, downEvent);
				if (downEvent2 != null)xInputEvent.invoke(mWindowManager, downEvent2);
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
		try {
			if (xRreezeRotation == null) {
				xRreezeRotation = XposedTools.findMethod(mWindowManager.getClass(), "freezeRotation", Integer.TYPE);
			}

			xRreezeRotation.invoke(mWindowManager, orientation);

		} catch (Throwable e) { e.printStackTrace(); }
	}
	
	Method xThawRotation;
	private void thawRotation() {
		try {
			if (xThawRotation == null) {
				xThawRotation = XposedTools.findMethod(mWindowManager.getClass(), "thawRotation");
			}

			xThawRotation.invoke(mWindowManager);

		} catch (Throwable e) { e.printStackTrace(); }
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
	
	private void runCustomApp(String app) {
        try {
            if (app == null || app.isEmpty()) {
                Toast.makeText(mContext, "No app configured", Toast.LENGTH_SHORT).show();
                return;
            }

            Intent i = Intent.parseUri(app, 0);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            mContext.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(mContext, "Cannot start app:"+app, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
	}
}
