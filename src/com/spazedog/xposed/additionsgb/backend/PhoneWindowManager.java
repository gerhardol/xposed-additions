/*
 * This file is part of the Xposed Additions Project: https://github.com/spazedog/xposed-additions
 *  
 * Copyright (c) 2014 Daniel Bergløv
 *
 * Xposed Additions is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * Xposed Additions is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with Xposed Additions. If not, see <http://www.gnu.org/licenses/>
 */

package com.spazedog.xposed.additionsgb.backend;

import java.util.ArrayList;
import java.util.List;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.ActivityManager.RecentTaskInfo;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.Surface;
import android.widget.Toast;

import com.spazedog.lib.reflecttools.ReflectTools;
import com.spazedog.lib.reflecttools.ReflectTools.ReflectClass;
import com.spazedog.lib.reflecttools.ReflectTools.ReflectException;
import com.spazedog.lib.reflecttools.ReflectTools.ReflectField;
import com.spazedog.lib.reflecttools.ReflectTools.ReflectMethod;
import com.spazedog.xposed.additionsgb.Common;
import com.spazedog.xposed.additionsgb.Common.Index;
import com.spazedog.xposed.additionsgb.backend.service.XServiceManager;

import de.robv.android.xposed.XC_MethodHook;

public class PhoneWindowManager {
	public static final String TAG = PhoneWindowManager.class.getName();
	
	public static void init() {
		if(Common.DEBUG) Log.d(TAG, "Adding Window Manager Hook");
		
		PhoneWindowManager hooks = new PhoneWindowManager();
		ReflectClass pwm = ReflectTools.getReflectClass("com.android.internal.policy.impl.PhoneWindowManager");
		
		pwm.inject(hooks.hook_constructor);
		pwm.inject("init", hooks.hook_init);
		pwm.inject("interceptKeyBeforeQueueing", hooks.hook_interceptKeyBeforeQueueing);
		pwm.inject("interceptKeyBeforeDispatching", hooks.hook_interceptKeyBeforeDispatching);
	}
	
	protected static Boolean SDK_NEW_POWER_MANAGER = android.os.Build.VERSION.SDK_INT >= 17;
	protected static Boolean SDK_NEW_PHONE_WINDOW_MANAGER = android.os.Build.VERSION.SDK_INT >= 11;
	protected static Boolean SDK_NEW_RECENT_APPS_DIALOG = android.os.Build.VERSION.SDK_INT >= 11;
	protected static Boolean SDK_NEW_CHARACTERMAP = android.os.Build.VERSION.SDK_INT >= 11;
	protected static Boolean SDK_HAS_HARDWARE_INPUT_MANAGER = android.os.Build.VERSION.SDK_INT >= 16;
	protected static Boolean SDK_HAS_MULTI_USER = android.os.Build.VERSION.SDK_INT >= 17;
	protected static Boolean SDK_HAS_KEYGUARD_DELEGATE = android.os.Build.VERSION.SDK_INT >= 19;
	protected static Boolean SDK_HAS_ROTATION_TOOLS = android.os.Build.VERSION.SDK_INT >= 11;
	
	protected static int ACTION_SLEEP_QUEUEING;
	protected static int ACTION_WAKEUP_QUEUEING;
	protected static int ACTION_PASS_QUEUEING;
	protected static int ACTION_DISABLE_QUEUEING;
	
	protected static Object ACTION_PASS_DISPATCHING;
	protected static Object ACTION_DISABLE_DISPATCHING;
	
	protected static int FLAG_INJECTED;
	protected static int FLAG_VIRTUAL;
	
	protected static int INJECT_INPUT_EVENT_MODE_ASYNC;
	
	protected static int FIRST_APPLICATION_UID;
	protected static int LAST_APPLICATION_UID;
	
	protected Context mContext;
	protected XServiceManager mPreferences;
	
	protected Handler mHandler;
	
	protected Object mPowerManager;				// android.os.PowerManager
	protected Object mPowerManagerService;		// android.os.IPowerManager (com.android.server.power.PowerManagerService)
	protected Object mWindowManager;			// android.view.WindowManager
	protected Object mPhoneWindowManager;		// com.android.internal.policy.impl.PhoneWindowManager
	protected Object mInputManager;				// android.hardware.input.InputManager
	protected Object mActivityManager;			// android.app.ActivityManager
	protected Object mActivityManagerService;	// android.app.ISDK_NEW_KEYEVENTActivityManager (android.app.ActivityManagerNative)
	protected Object mAudioManager;
	
	protected boolean mReady = false;
	
	protected KeyFlags mKeyFlags = new KeyFlags();
	protected KeyConfig mKeyConfig = new KeyConfig();
	
	protected Object mLockQueueing = new Object();
	
	protected Boolean mWasScreenOn = true;
	
	protected Boolean mSupportsVirtualDetection = false;
	
	protected Intent mTorchIntent;
	
	protected WakeLock mWakelock;
	
	protected XC_MethodHook hook_constructor = new XC_MethodHook() {
		@Override
		protected final void afterHookedMethod(final MethodHookParam param) {
			if(Common.debug()) Log.d(TAG, "Handling construct of the Window Manager instance");
			
			ReflectClass wmp = ReflectTools.getReflectClass("android.view.WindowManagerPolicy");
			ReflectClass process = ReflectTools.getReflectClass("android.os.Process");
			
			FLAG_INJECTED = (Integer) wmp.getField("FLAG_INJECTED").get();
			FLAG_VIRTUAL = (Integer) wmp.getField("FLAG_VIRTUAL").get();
			
			ACTION_SLEEP_QUEUEING = (Integer) wmp.getField("ACTION_GO_TO_SLEEP").get();
			ACTION_WAKEUP_QUEUEING = (Integer) wmp.getField( SDK_NEW_POWER_MANAGER ? "ACTION_WAKE_UP" : "ACTION_POKE_USER_ACTIVITY" ).get();
			ACTION_PASS_QUEUEING = (Integer) wmp.getField("ACTION_PASS_TO_USER").get();
			ACTION_DISABLE_QUEUEING = 0;
			
			ACTION_PASS_DISPATCHING = SDK_NEW_PHONE_WINDOW_MANAGER ? 0 : false;
			ACTION_DISABLE_DISPATCHING = SDK_NEW_PHONE_WINDOW_MANAGER ? -1 : true;
			
			FIRST_APPLICATION_UID = (Integer) process.getField("FIRST_APPLICATION_UID").get();
			LAST_APPLICATION_UID = (Integer) process.getField("LAST_APPLICATION_UID").get();
					
			if (SDK_HAS_HARDWARE_INPUT_MANAGER) {
				INJECT_INPUT_EVENT_MODE_ASYNC = (Integer) ReflectTools.getReflectClass("android.hardware.input.InputManager").getField("INJECT_INPUT_EVENT_MODE_ASYNC").get();
			}
		}
	};
	
	protected Thread locateTorchApps = new Thread() {
		@Override
		public void run() {
			try {
				/*
				 * If the ROM has CM Torch capabilities, then use that instead. 
				 * 
				 * Some ROM's who implements some of CM's capabilities, some times changes the name of this util.cm folder to match 
				 * their name. In these cases we don't care about consistency. If you are going to borrow from others, 
				 * then make sure to keep compatibility.
				 */
				ReflectClass torchConstants = ReflectTools.getReflectClass("com.android.internal.util.cm.TorchConstants");
				mTorchIntent = new Intent((String) torchConstants.locateField("ACTION_TOGGLE_STATE").get());
				
			} catch (ReflectException re) {
				/*
				 * Search for Torch Apps that supports <package name>.TOGGLE_FLASHLIGHT intents
				 */
				PackageManager pm = mContext.getPackageManager();
				List<PackageInfo> packages = pm.getInstalledPackages(0);
				
				for (PackageInfo pkg : packages) {
					Intent intent = new Intent(pkg.packageName + ".TOGGLE_FLASHLIGHT");
					List<ResolveInfo> recievers = pm.queryBroadcastReceivers(intent, 0);
					
					if (recievers.size() > 0) {
						mTorchIntent = intent; break;
					}
				}
			}
		}
	};
	
	/**
	 * ========================================================================================
	 * Gingerbread uses arguments init(Context, IWindowManager, LocalPowerManager)
	 * ICS uses arguments init(Context, IWindowManager, WindowManagerFuncs, LocalPowerManager)
	 * JellyBean uses arguments init(Context, IWindowManager, WindowManagerFuncs)
	 */
	protected XC_MethodHook hook_init = new XC_MethodHook() {
		@Override
		protected final void afterHookedMethod(final MethodHookParam param) {
			mContext = (Context) param.args[0];
			mPowerManager = mContext.getSystemService(Context.POWER_SERVICE);
			mPowerManagerService = ReflectTools.getReflectClass(mPowerManager).getField("mService").get(mPowerManager);
			mWindowManager = param.args[1];
			mPhoneWindowManager = param.thisObject;
			mActivityManager = mContext.getSystemService(Context.ACTIVITY_SERVICE);
			mAudioManager = mContext.getSystemService(Context.AUDIO_SERVICE);
			
			mHandler = new Handler();
			
			mPreferences = XServiceManager.getInstance();
			mPreferences.registerContext(mContext);
			
			mWakelock = ((PowerManager) mPowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HookedPhoneWindowManager");
			
			mContext.registerReceiver(
				new BroadcastReceiver() {
					@Override
					public void onReceive(Context context, Intent intent) {
						mReady = true;
						mContext.unregisterReceiver(this);
						
						/*
						 * It is also best to wait with this one
						 */
						mActivityManagerService = ReflectTools.getReflectClass("android.app.ActivityManagerNative").getMethod("getDefault").invoke();
						
						if (SDK_HAS_HARDWARE_INPUT_MANAGER) {
							/*
							 * This cannot be placed in hook_init because it is to soon to call InputManager#getInstance.
							 * If we do, we will get a broken IBinder which will crash both this module along
							 * with anything else trying to access the InputManager methods.
							 */
							mInputManager = ReflectTools.getReflectClass("android.hardware.input.InputManager").getMethod("getInstance").invoke();
						}
						
						if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
							/*
							 * This could take awhile depending on the amount of apps installed. 
							 * We use a separate thread instead of the handler to avoid blocking any key events. 
							 */
							locateTorchApps.start();
						}
					}
				}, new IntentFilter("android.intent.action.BOOT_COMPLETED")
			);
		}
	};
	
	/**
	 * ========================================================================================
	 * Gingerbread uses arguments interceptKeyBeforeQueueing(Long whenNanos, Integer action, Integer flags, Integer keyCode, Integer scanCode, Integer policyFlags, Boolean isScreenOn)
	 * ICS/JellyBean uses arguments interceptKeyBeforeQueueing(KeyEvent event, Integer policyFlags, Boolean isScreenOn)
	 */
	protected XC_MethodHook hook_interceptKeyBeforeQueueing = new XC_MethodHook() {
		@Override
		protected final void beforeHookedMethod(final MethodHookParam param) {
			/*
			 * Do nothing until the device is done booting
			 */
			if (!mReady) {
				param.setResult(ACTION_DISABLE_QUEUEING);
				
				return;
			}

			final int action = (Integer) (!SDK_NEW_PHONE_WINDOW_MANAGER ? param.args[1] : ((KeyEvent) param.args[0]).getAction());
			final int policyFlags = (Integer) (!SDK_NEW_PHONE_WINDOW_MANAGER ? param.args[5] : param.args[1]);
			final int keyCode = (Integer) (!SDK_NEW_PHONE_WINDOW_MANAGER ? param.args[3] : ((KeyEvent) param.args[0]).getKeyCode());
			final int repeatCount = (Integer) (!SDK_NEW_PHONE_WINDOW_MANAGER ? 0 : ((KeyEvent) param.args[0]).getRepeatCount());
			final boolean isScreenOn = (Boolean) (!SDK_NEW_PHONE_WINDOW_MANAGER ? param.args[6] : param.args[2]);
			final boolean down = action == KeyEvent.ACTION_DOWN;
			
			String tag = TAG + "#Queueing/" + (down ? "Down" : "Up") + ":" + keyCode;
			
			/*
			 * Using KitKat work-around from the InputManager Hook
			 */
			final boolean isInjected = SDK_HAS_HARDWARE_INPUT_MANAGER ? 
					(((KeyEvent) param.args[0]).getFlags() & FLAG_INJECTED) != 0 : (policyFlags & FLAG_INJECTED) != 0;
			
			if (isInjected) {
				if (!down || repeatCount <= 0) {
					if(Common.debug()) Log.d(tag, "Skipping injected key");
					
					if (SDK_NEW_PHONE_WINDOW_MANAGER) {
						param.args[1] = policyFlags & ~FLAG_INJECTED;
						
					} else {
						param.args[5] = policyFlags & ~FLAG_INJECTED;
					}
					
				} else {
					if(Common.debug()) Log.d(tag, "Ignoring injected repeated key");
					
					/*
					 * Normally repeated events will not continue to invoke this method. 
					 * But it seams that repeating an event using injection will. On most devices
					 * the original methods themselves seams to be handling this just fine, but a few 
					 * stock ROM's are treating these as both new and repeated events. 
					 */
					param.setResult(ACTION_PASS_QUEUEING);
				}
			
				return;
			}
			
			synchronized(mLockQueueing) {
				if(Common.debug()) Log.d(tag, (down ? "Starting" : "Stopping") + " event");
				
				boolean isVirtual = (policyFlags & FLAG_VIRTUAL) != 0;
				
				/*
				 * Some Stock ROM's has problems detecting virtual keys. 
				 * Some of them just hard codes the keys into the class. 
				 * This provides a way to force a key being detected as virtual. 
				 */
				if (down && !isVirtual) {
					List<String> forcedKeys = (ArrayList<String>) mPreferences.getStringArray(Index.array.key.forcedHapticKeys, Index.array.value.forcedHapticKeys);
					
					if (forcedKeys.contains(""+keyCode)) {
						isVirtual = true;
					}
				}
				
				if (down && isVirtual) {
					performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
				}
				
				if (isScreenOn && mKeyFlags.isDone() && mPreferences.getBoolean("intercept_keycode", false)) {
					//Intercept code during configuration
					if (down) {
						if(Common.debug()) Log.d(tag, "Intercepting key code");
						
						mPreferences.putInt("last_intercepted_keycode", keyCode, false);
						
						mKeyFlags.reset();
					}
					
					param.setResult(ACTION_DISABLE_QUEUEING);
					
				} else {
					if (down && isScreenOn != mWasScreenOn) {
						mWasScreenOn = isScreenOn;
						
						mKeyFlags.reset();
					}
					
					mKeyFlags.registerKey(keyCode, down);
					
					if (down) {
						//First tap
						if (mKeyFlags.getTaps() == 1) {
							if(Common.debug()) Log.d(tag, "Configuring event");
							
							mWasScreenOn = isScreenOn;
							
							mKeyConfig.register(mKeyFlags, isScreenOn);

							if (!isScreenOn) {
								pokeUserActivity(false);
							}
						}
					}
					
					if(Common.debug()) Log.d(tag, "Parsing event to dispatcher");
					
					param.setResult(ACTION_PASS_QUEUEING);
				}
			}
		}
	};
	
	/**
	 * ========================================================================================
	 * Gingerbread uses arguments interceptKeyBeforeDispatching(WindowState win, Integer action, Integer flags, Integer keyCode, Integer scanCode, Integer metaState, Integer repeatCount, Integer policyFlags)
	 * ICS/JellyBean uses arguments interceptKeyBeforeDispatching(WindowState win, KeyEvent event, Integer policyFlags)
	 */
	protected XC_MethodHook hook_interceptKeyBeforeDispatching = new XC_MethodHook() {
		@Override
		protected final void beforeHookedMethod(final MethodHookParam param) {
			/*
			 * Do nothing until the device is done booting
			 */
			if (!mReady) {
				param.setResult(ACTION_DISABLE_DISPATCHING);
				
				return;
			}
			
			final int keyCode = (Integer) (!SDK_NEW_PHONE_WINDOW_MANAGER ? param.args[3] : ((KeyEvent) param.args[1]).getKeyCode());
			final int action = (Integer) (!SDK_NEW_PHONE_WINDOW_MANAGER ? param.args[1] : ((KeyEvent) param.args[1]).getAction());
			final int policyFlags = (Integer) (!SDK_NEW_PHONE_WINDOW_MANAGER ? param.args[7] : param.args[2]);
			//final int eventFlags = (Integer) (!SDK_NEW_PHONE_WINDOW_MANAGER ? param.args[2] : ((KeyEvent) param.args[1]).getFlags());
			final int repeatCount = (Integer) (!SDK_NEW_PHONE_WINDOW_MANAGER ? param.args[6] : ((KeyEvent) param.args[1]).getRepeatCount());
			final boolean down = action == KeyEvent.ACTION_DOWN;
			
			String tag = TAG + "#Dispatch/" + (down ? "Down" : "Up") + ":" + keyCode;
			
			/*
			 * Using KitKat work-around from the InputManager Hook
			 */
			final boolean isInjected = SDK_HAS_HARDWARE_INPUT_MANAGER ? 
					(((KeyEvent) param.args[1]).getFlags() & FLAG_INJECTED) != 0 : (policyFlags & FLAG_INJECTED) != 0;
			
			if (isInjected) {
				if (SDK_NEW_PHONE_WINDOW_MANAGER) {
					param.args[2] = policyFlags & ~FLAG_INJECTED;
					
				} else {
					param.args[7] = policyFlags & ~FLAG_INJECTED;
				}
				
				if (down && mKeyFlags.isDefaultLongPress() && mKeyFlags.isKeyDown() && keyCode == mKeyFlags.getCurrentKey()) {
					if(Common.debug()) Log.d(tag, "Repeating default long press event count " + repeatCount);
					
					injectInputEvent(keyCode, repeatCount+1);
				}
			
				return;
				
			} else if (!down && mKeyFlags.isDefaultLongPress()) {
				if(Common.debug()) Log.d(tag, "Releasing default long press event");
				
				injectInputEvent(keyCode, -1);
				
			} else if (!mKeyFlags.wasInvoked()) {
				if(Common.debug()) Log.d(tag, (down ? "Starting" : "Stopping") + " event");
				
				if (down && (mKeyFlags.getTaps() == 1)) {
					if(Common.debug()) Log.d(tag, "Waiting for long press timeout");
					
					Boolean wasMulti = mKeyFlags.isMulti();
					Integer curDelay = 0;
					Integer pressDelay = mKeyConfig.getLongPressDelay();
							
					do {
						try {
							Thread.sleep(10);
							
						} catch (Throwable e) {}
						
						curDelay += 10;
						
					} while (mKeyFlags.isKeyDown() && keyCode == mKeyFlags.getCurrentKey() && wasMulti == mKeyFlags.isMulti() && curDelay < pressDelay);
					
					synchronized(mLockQueueing) {
						if (mKeyFlags.isKeyDown() && keyCode == mKeyFlags.getCurrentKey() && wasMulti == mKeyFlags.isMulti()) {
							if (mKeyConfig.hasAction(ActionTypes.press, mKeyFlags)) {
								if(Common.debug()) Log.d(tag, "Invoking mapped long press action");
								
								performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
								handleKeyAction(mKeyConfig.getAction(ActionTypes.press, mKeyFlags), keyCode);
								
								mKeyFlags.finish();
								
							} else {
								if(Common.debug()) Log.d(tag, "Invoking default long press action");
								
								mKeyFlags.setDefaultLongPress(true);
								mKeyFlags.finish();
								
								if (mKeyFlags.isMulti()) {
									int primary = mKeyFlags.getPrimaryKey() == keyCode ? mKeyFlags.getSecondaryKey() : mKeyFlags.getPrimaryKey();
									
									injectInputEvent(primary, 0);
								}
								
								injectInputEvent(keyCode, 0); // Force trigger default long press
								
								/*
								 * The original methods will start by getting a 0 repeat event in order to prepare. 
								 * Applications that use the tracking flag will need to original, as they cannot start 
								 * tracking from an injected key. 
								 */
								param.setResult(ACTION_PASS_DISPATCHING);
		
								return;
							}
						}
					}
					
				} else if (down) {
					if(Common.debug()) Log.d(tag, "Invoking double tap action");
					
					handleKeyAction(mKeyConfig.getAction(ActionTypes.tap, mKeyFlags), keyCode);
					
					mKeyFlags.finish();
	
				} else {
					if (mKeyConfig.hasAction(ActionTypes.tap, mKeyFlags)) {
						if(Common.debug()) Log.d(tag, "Waiting for double tap timeout");
						
						int curDelay = 0;
						
						do {
							try {
								Thread.sleep(10);
								
							} catch (Throwable e) {}
							
							curDelay += 10;
							
						} while (!mKeyFlags.isKeyDown() && curDelay < mKeyConfig.getDoubleTapDelay());
					}
					
					synchronized(mLockQueueing) {
						if (!mKeyFlags.isKeyDown() && mKeyFlags.getCurrentKey() == keyCode) {
							int callCode = 0;
							
							if (mKeyFlags.isCallButton()) {
								int mode = ((AudioManager) mAudioManager).getMode();
								
								if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
									callCode = KeyEvent.KEYCODE_ENDCALL;
									
								} else if (mode == AudioManager.MODE_RINGTONE) {
									callCode = KeyEvent.KEYCODE_CALL;
								}
							}
							
							if (callCode == 0) {
								if(Common.debug()) Log.d(tag, "Invoking single click action");
								
								handleKeyAction(mKeyConfig.getAction(ActionTypes.tap, mKeyFlags), keyCode);
								
							} else {
								if(Common.debug()) Log.d(tag, "Invoking call button");
								
								injectInputEvent(callCode);
							}
							
							mKeyFlags.finish();
						}
					}
				}
			}
			
			param.setResult(ACTION_DISABLE_DISPATCHING);
		}
	};
	
	@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
	public void pokeUserActivity(Boolean forced) {
		if (forced) {
			if (SDK_NEW_POWER_MANAGER) {
				((PowerManager) mPowerManager).wakeUp(SystemClock.uptimeMillis());
				
			} else {
				/*
				 * API's below 17 does not support PowerManager#wakeUp, so
				 * instead we will trick our way into the hidden IPowerManager#forceUserActivityLocked which 
				 * is not accessible trough the regular PowerManager class. It the same method that 
				 * turns on the screen when you plug in your USB cable.
				 */
				ReflectTools.getReflectClass(mPowerManagerService).locateMethod("forceUserActivityLocked").invoke(mPowerManagerService);
			}
			
		} else {
			if (!mWakelock.isHeld()) {
				mWakelock.acquire(3000);
			}
		}
	}

	@SuppressLint("NewApi")
	protected void changeDisplayState(Boolean on) {
		if (on) {
			pokeUserActivity(true);
			
		} else {
			((PowerManager) mPowerManager).goToSleep(SystemClock.uptimeMillis());
		}
	}
	
	ReflectMethod xInjectInputEvent;
	@SuppressLint("NewApi")
	protected void injectInputEvent(final int keyCode, final int... repeat) {
		mHandler.post(new Runnable() {
			public void run() {
				synchronized(PhoneWindowManager.class) {
					if (xInjectInputEvent == null) {
						xInjectInputEvent = SDK_HAS_HARDWARE_INPUT_MANAGER ? 
								ReflectTools.getReflectClass(mInputManager).locateMethod("injectInputEvent", ReflectTools.MEMBER_MATCH_FAST, KeyEvent.class, Integer.TYPE) : 
									ReflectTools.getReflectClass(mWindowManager).locateMethod("injectInputEventNoWait", ReflectTools.MEMBER_MATCH_FAST, KeyEvent.class);
					}
					
					long now = SystemClock.uptimeMillis();
					int characterMap = SDK_NEW_CHARACTERMAP ? KeyCharacterMap.VIRTUAL_KEYBOARD : 0;
					int eventType = repeat.length == 0 || repeat[0] >= 0 ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
					
					int flags = repeat.length > 0 && repeat[0] == 1 ? KeyEvent.FLAG_LONG_PRESS|KeyEvent.FLAG_FROM_SYSTEM|FLAG_INJECTED : KeyEvent.FLAG_FROM_SYSTEM|FLAG_INJECTED;
					
					int repeatCount = repeat.length == 0 ? 0 : 
						repeat[0] < 0 ? 1 : repeat[0];
						
					KeyEvent event = new KeyEvent(now, now, eventType, keyCode, repeatCount, 0, characterMap, 0, flags, InputDevice.SOURCE_UNKNOWN);
					
					if (SDK_HAS_HARDWARE_INPUT_MANAGER) {
						xInjectInputEvent.invoke(mInputManager, false, event, INJECT_INPUT_EVENT_MODE_ASYNC);
						
					} else {
						xInjectInputEvent.invoke(mWindowManager, false, event);
					}
					
					if (repeat.length == 0) {
						if (SDK_HAS_HARDWARE_INPUT_MANAGER) {
							xInjectInputEvent.invoke(mInputManager, false, KeyEvent.changeAction(event, KeyEvent.ACTION_UP), INJECT_INPUT_EVENT_MODE_ASYNC);
							
						} else {
							xInjectInputEvent.invoke(mWindowManager, false, KeyEvent.changeAction(event, KeyEvent.ACTION_UP));
						}
					}
				}
			}
		});
	}

	ReflectMethod xPerformHapticFeedbackLw;
	protected void performHapticFeedback(Integer effectId) {
		if (xPerformHapticFeedbackLw == null) {
			xPerformHapticFeedbackLw = ReflectTools.getReflectClass(mPhoneWindowManager)
					.locateMethod("performHapticFeedbackLw", ReflectTools.MEMBER_MATCH_FAST, "android.view.WindowManagerPolicy$WindowState", Integer.TYPE, Boolean.TYPE);
		}
		
		xPerformHapticFeedbackLw.invoke(mPhoneWindowManager, false, null, effectId, false);
	}
	
	ReflectField xKeyguardMediator;
	protected Object getKeyguardMediator() {
		if (xKeyguardMediator == null) {
			if (SDK_HAS_KEYGUARD_DELEGATE) {
				xKeyguardMediator = ReflectTools.getReflectClass(mPhoneWindowManager).locateField("mKeyguardDelegate");
				
			} else {
				xKeyguardMediator = ReflectTools.getReflectClass(mPhoneWindowManager).locateField("mKeyguardMediator");
			}
		}
		
		return xKeyguardMediator.get(mPhoneWindowManager);
	}
	
	ReflectMethod xIsShowingAndNotHidden;
	ReflectMethod xIsInputRestricted;
	protected Boolean isKeyguardShowing() {
		Object keyguardMediator = getKeyguardMediator();
		
		if (xIsShowingAndNotHidden == null || xIsInputRestricted == null) {
			xIsShowingAndNotHidden = ReflectTools.getReflectClass(keyguardMediator).locateMethod("isShowingAndNotHidden");
			xIsInputRestricted = ReflectTools.getReflectClass(keyguardMediator).locateMethod("isInputRestricted");
		}
		
		return (Boolean) xIsShowingAndNotHidden.invoke(keyguardMediator) || (Boolean) xIsInputRestricted.invoke(keyguardMediator);
	}
	
	ReflectMethod xIsShowing;
	protected Boolean isKeyguardLocked() {
		Object keyguardMediator = getKeyguardMediator();
		
		if (xIsShowing == null) {
			xIsShowing = ReflectTools.getReflectClass(keyguardMediator).locateMethod("isShowing");
		}
		
		return (Boolean) xIsShowing.invoke(keyguardMediator);
	}
	
	protected void keyGuardDismiss() {
		final Object keyguardMediator = getKeyguardMediator();
		final Boolean isShowing = (Boolean) ReflectTools.getReflectClass(keyguardMediator).locateMethod("isShowing").invoke(keyguardMediator);
		
		if (isShowing) {
			ReflectTools.getReflectClass(keyguardMediator).locateMethod("keyguardDone", ReflectTools.MEMBER_MATCH_FAST, Boolean.TYPE, Boolean.TYPE).invoke(keyguardMediator, false, false, true);
		}
	}
	
	protected String getRunningPackage() {
		List<ActivityManager.RunningTaskInfo> packages = ((ActivityManager) mActivityManager).getRunningTasks(1);
		
		return packages.size() > 0 ? packages.get(0).baseActivity.getPackageName() : null;
	}
	
	protected String getHomePackage() {
		Intent intent = new Intent(Intent.ACTION_MAIN);
		intent.addCategory(Intent.CATEGORY_HOME);
		ResolveInfo res = mContext.getPackageManager().resolveActivity(intent, 0);
		
		return res.activityInfo != null && !"android".equals(res.activityInfo.packageName) ? 
				res.activityInfo.packageName : "com.android.launcher";
	}
	
	protected void handleDefaultQueueing(int flag) {
		if ((flag & ACTION_SLEEP_QUEUEING) != 0) {
			changeDisplayState(false);
			
		} else if ((flag & ACTION_WAKEUP_QUEUEING) != 0) {
			changeDisplayState(true);
		}
	}
	
	protected void launchApplication(String packageName) {
		Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(packageName);
		
		keyGuardDismiss();
		
		if (intent != null) {
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
			
		} else {
			/*
			 * In case the app has been deleted after button setup
			 */
			intent = new Intent(Intent.ACTION_VIEW);
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			intent.setData(Uri.parse("market://details?id="+packageName));
		}
		
		mContext.startActivity(intent);
	}
	
	protected void toggleLastApplication() {
		List<RecentTaskInfo> packages = ((ActivityManager) mActivityManager).getRecentTasks(5, ActivityManager.RECENT_WITH_EXCLUDED);
		
		for (int i=1; i < packages.size(); i++) {
			String intentString = packages.get(i).baseIntent + "";
			
			int indexStart = intentString.indexOf("cmp=")+4;
		    int indexStop = intentString.indexOf("/", indexStart);
			
			String packageName = intentString.substring(indexStart, indexStop);
			
			if (!packageName.equals(getHomePackage()) && !packageName.equals("com.android.systemui")) {
				mContext.startActivity(packages.get(i).baseIntent);
			}
		}
	}
	
	protected void sendCloseSystemWindows(String reason) {
		if(Common.debug()) Log.d(TAG, "Closing all system windows");
		
		try {
			ReflectTools.getReflectClass(mActivityManagerService).locateMethod("closeSystemDialogs", ReflectTools.MEMBER_MATCH_FAST, String.class).invoke(mActivityManagerService, false, reason);
			
		} catch (Throwable e) {
			if (Common.debug()) {
				throw new Error(e.getMessage(), e);
			}
		}
	}
	
	Object xRecentAppsService;
	protected void openRecentAppsDialog() {
		if(Common.debug()) Log.d(TAG, "Invoking Recent Application Dialog");
		
		sendCloseSystemWindows("recentapps");
		
		try {
			if (SDK_NEW_RECENT_APPS_DIALOG) {
				if (xRecentAppsService == null) {
					Object binder = ReflectTools.getReflectClass("android.os.ServiceManager").getMethod("getService", ReflectTools.MEMBER_MATCH_FAST, String.class).invoke(false, "statusbar");
					xRecentAppsService = ReflectTools.getReflectClass("com.android.internal.statusbar.IStatusBarService$Stub").getMethod("asInterface", ReflectTools.MEMBER_MATCH_FAST, IBinder.class).invoke(false, binder);
				}

				ReflectTools.getReflectClass(xRecentAppsService).getMethod("toggleRecentApps").invoke(xRecentAppsService);
				
			} else {
				if (xRecentAppsService == null) {
					xRecentAppsService = ReflectTools.getReflectClass("com.android.internal.policy.impl.RecentApplicationsDialog").invoke(false, mContext);
				}
				
				ReflectTools.getReflectClass(xRecentAppsService).getMethod("show").invoke(xRecentAppsService);
			}
			
		} catch (Throwable e) {
			xRecentAppsService = null;
			
			if (Common.debug()) {
				throw new Error(e.getMessage(), e);
			}
		}
	}
	
	protected void openGlobalActionsDialog() {
		if(Common.debug()) Log.d(TAG, "Invoking Global Actions Dialog");
		
		sendCloseSystemWindows("globalactions");
		
		try {
			ReflectTools.getReflectClass(mPhoneWindowManager).locateMethod("showGlobalActionsDialog").invoke(mPhoneWindowManager);
			
		} catch (Throwable e) {
			if (Common.debug()) {
				throw new Error(e.getMessage(), e);
			}
		}
	}
	
	protected void freezeRotation(Integer orientation) {
		if (SDK_HAS_ROTATION_TOOLS) {
			if (orientation != 1) {
				switch (orientation) {
					case 90: orientation = Surface.ROTATION_90; break;
					case 180: orientation = Surface.ROTATION_180; break;
					case 270: orientation = Surface.ROTATION_270;
				}
				
				ReflectTools.getReflectClass(mWindowManager).locateMethod("freezeRotation", ReflectTools.MEMBER_MATCH_FAST, Integer.TYPE).invoke(mWindowManager, false, orientation);
				
			} else {
				ReflectTools.getReflectClass(mWindowManager).locateMethod("thawRotation").invoke(mWindowManager);
			}
			
		} else {
			/*
			 * TODO: Find a working way for locking Gingerbread in a specific orientation
			 */
			Settings.System.putInt(mContext.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, orientation != 1 ? 1 : 0);
		}
	}
	
	protected Boolean isRotationLocked() {
        return Settings.System.getInt(mContext.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 0;
	}
	
	protected Integer getCurrentRotation() {
		return (Integer) ReflectTools.getReflectClass(mWindowManager).locateMethod("getRotation").invoke(mWindowManager);
	}
	
	protected Integer getNextRotation(Boolean backwards) {
		Integer  position = getCurrentRotation();
		
		return (position == Surface.ROTATION_90 || position == Surface.ROTATION_0) && backwards ? 270 : 
			(position == Surface.ROTATION_270 || position == Surface.ROTATION_0) && !backwards ? 90 : 0;
	}
	
	ReflectMethod xForceStopPackage;
	protected void killForegroundApplication() {
		if(Common.debug()) Log.d(TAG, "Start searching for foreground application to kill");
		
		if (xForceStopPackage == null) {
			xForceStopPackage = ReflectTools.getReflectClass(mActivityManagerService).locateMethod("forceStopPackage", ReflectTools.MEMBER_MATCH_FAST, 
					SDK_HAS_MULTI_USER ? new Object[]{String.class, Integer.TYPE} : new Object[]{String.class});
		}
		
		List<ActivityManager.RunningTaskInfo> packages = ((ActivityManager) mActivityManager).getRunningTasks(5);
		
		for (int i=0; i < packages.size(); i++) {
			String packageName = packages.get(0).baseActivity.getPackageName();
			
			if (!packageName.equals(getHomePackage()) && !packageName.equals("com.android.systemui")) {
				if(Common.debug()) Log.d(TAG, "Invoking force stop on " + packageName);
				
				if (SDK_HAS_MULTI_USER) {
					xForceStopPackage.invoke(mActivityManagerService, false, packageName, ReflectTools.getReflectClass("android.os.UserHandle").getField("USER_CURRENT").get());
					
				} else {
					xForceStopPackage.invoke(mActivityManagerService, false, packageName);
				}
			}
		}
	}
	
	protected void takeScreenshot() {
		try {
			ReflectTools.getReflectClass(mPhoneWindowManager).locateMethod("takeScreenshot").invoke(mPhoneWindowManager);
			
		} catch (Throwable e) {
			if (Common.debug()) {
				throw new Error(e.getMessage(), e);
			}
		}
	}
	
	protected void toggleFlashLight() {
		if (mTorchIntent != null) {
			mContext.sendBroadcast(mTorchIntent);
		}
	}
	
	protected void handleKeyAction(final String action, final Integer keyCode) {
		final Integer code = action != null && action.matches("^[0-9]+$") ? Integer.parseInt(action) : 
			action == null ? keyCode : 0;
		
		/*
		 * We handle display on here, because some devices has issues
		 * when executing handlers while in deep sleep. 
		 * Some times they will need a few key presses before reacting. 
		 */
		if (code == KeyEvent.KEYCODE_POWER && !mWasScreenOn) {
			changeDisplayState(true); return;
		}
		
		/*
		 * This should always be wrapped and sent to a handler. 
		 * If this is executed directly, some of the actions will crash with the error 
		 * -> 'Can't create handler inside thread that has not called Looper.prepare()'
		 */
		mHandler.post(new Runnable() {
			public void run() {
				String actionType = Common.actionType(action);
				
				if ("launcher".equals(actionType)) {
					launchApplication(action);
					
				} else if ("custom".equals(actionType)) {
					if (!"disabled".equals(action)) {
						if ("recentapps".equals(action)) {
							openRecentAppsDialog();
							
						} else if ("powermenu".equals(action)) {
							openGlobalActionsDialog();
							
						} else if ("flipleft".equals(action)) {
							freezeRotation( getNextRotation(true) );
							
						} else if ("flipright".equals(action)) {
							freezeRotation( getNextRotation(false) );
							
						} else if ("fliptoggle".equals(action)) {
							if (isRotationLocked()) {
								Toast.makeText(mContext, "Rotation has been Enabled", Toast.LENGTH_SHORT).show();
								freezeRotation(1);
								
							} else {
								Toast.makeText(mContext, "Rotation has been Disabled", Toast.LENGTH_SHORT).show();
								freezeRotation(-1);
							}
							
						} else if ("killapp".equals(action)) {
							killForegroundApplication();
							
						} else if ("guarddismiss".equals(action)) {
							keyGuardDismiss();
							
						} else if ("screenshot".equals(action)) {
							takeScreenshot();
							
						} else if ("torch".equals(action)) {
							toggleFlashLight();
							
						} else if ("lastapp".equals(action)) {
							toggleLastApplication();
						}
					}
					
				} else {
					injectInputEvent(code);
				}
			}
		});
	}
	
	protected enum ActionTypes { press, tap };
	
	protected class KeyConfig {
		protected static final int maxTapActions = 3;
		
		//actions in the order they appear: press 1, tap 1, press 2, tap 2 etc
		private String[] mActions = new String[maxTapActions*2];
		private Integer mKeyDelayTap = 0;
		private Integer mKeyDelayPress = 0;
		
		public void register(KeyFlags keyFlags, Boolean isScreenOn)
		{
			Boolean extended = mPreferences.isPackageUnlocked();
			this.mKeyDelayTap = mPreferences.getInt(Common.Index.integer.key.remapTapDelay, Common.Index.integer.value.remapTapDelay);
			this.mKeyDelayPress = mPreferences.getInt(Common.Index.integer.key.remapPressDelay, Common.Index.integer.value.remapPressDelay);
			List<String> actions = null;

			if (!mKeyFlags.isMulti() || extended) {
				String keyGroupName = mKeyFlags.getPrimaryKey() + ":" + mKeyFlags.getSecondaryKey();
				String appCondition = !isScreenOn ? null : 
					isKeyguardShowing() ? "guard" : extended ? getRunningPackage() : null;

			    actions = appCondition != null ? mPreferences.getStringArrayGroup(String.format(Index.array.groupKey.remapKeyActions_$, appCondition), keyGroupName, null) : null;

				if (actions == null) {
					actions = mPreferences.getStringArrayGroup(String.format(Index.array.groupKey.remapKeyActions_$, isScreenOn ? "on" : "off"), keyGroupName, null);
				}
			}

			//The actions are not stored in order
			String clickAction = actions != null && actions.size() > 0 ? actions.get(0) : null;
			String tapAction   = actions != null && actions.size() > 1 ? actions.get(1) : null;
			String pressAction = actions != null && actions.size() > 2 ? actions.get(2) : null;

			mActions[0] = extended || (pressAction != null && !pressAction.contains(".")) ? pressAction : null;
			mActions[1] = extended || (clickAction != null && !clickAction.contains(".")) ? clickAction : null;
			mActions[2] = null;
			mActions[3] = extended || (tapAction != null && !tapAction.contains(".")) ? tapAction : null;
			mActions[4] = null;
			mActions[5] = null;
		}
		
		private int getIndex(ActionTypes atype, KeyFlags keyFlags) {
			int index = (keyFlags.getTaps() -1)*2;
			if (atype == ActionTypes.tap) {index++;}
			return index;			
		}
		
		public String getAction(ActionTypes atype, KeyFlags keyFlags) {
			int index = getIndex(atype, keyFlags);
			if(index > mActions.length) {return null;}
			return mActions[index];
		}
		
		public Boolean hasAction(ActionTypes atype, KeyFlags keyFlags) {
			return (getAction(atype, keyFlags) != null);
		}
		
		public Boolean hasMoreAction(ActionTypes atype, KeyFlags keyFlags) {
			Boolean result = false;
			int index = getIndex(atype, keyFlags);
			while (index < maxTapActions) {
				if (mActions[index] != null) {
					result = true;
					break;
				}
				index++;
			}
			return result;
		}
		
		public Integer getDoubleTapDelay() {
			return mKeyDelayTap;
		}
		
		public Integer getLongPressDelay() {
			return mKeyDelayPress;
		}
	}
	
	protected class KeyFlags {
		private Boolean mIsPrimaryDown = false;
		private Boolean mIsSecondaryDown = false;
		private Boolean mFinished = false;
		private Boolean mReset = false;
		private Boolean mDefaultLongPress = false;
		private Boolean mIsCallButton = false;
		
		private Integer mTaps = 0;
		private Integer mPrimaryKey = 0;
		private Integer mSecondaryKey = 0;
		private Integer mCurrentKey = 0;
		
		public void finish() {
			mFinished = true;
			mReset = mSecondaryKey == 0;
		}
		
		public void reset() {
			mReset = true;
		}
		
		public void registerKey(Integer keyCode, Boolean down) {
			mCurrentKey = keyCode;

			String tag = TAG + "#KeyFlags:" + keyCode;
					
			if (down) {
				if (!isDone() && mTaps == 0 && (keyCode == mPrimaryKey || keyCode == mSecondaryKey)) {
					if(Common.debug()) Log.d(tag, "Registring repeated event");
					
					mTaps++;
					
					if (keyCode == mSecondaryKey) {
						mIsSecondaryDown = true;
						
					} else {
						mIsPrimaryDown = true;
					}
					
				} else if (mTaps == 0 && !mReset && mPrimaryKey > 0 && mIsPrimaryDown && keyCode != mPrimaryKey && (mSecondaryKey == 0 || mSecondaryKey == keyCode)) {
					if(Common.debug()) Log.d(tag, "Registring secondary key");
					
					mIsSecondaryDown = true;
					mFinished = false;
					
					mSecondaryKey = keyCode;
					
				} else {
					if(Common.debug()) Log.d(tag, "Registring primary key");
					
					mIsPrimaryDown = true;
					mIsSecondaryDown = false;
					mTaps = 0;
					mFinished = false;
					mReset = false;
					mDefaultLongPress = false;
					
					mPrimaryKey = keyCode;
					mSecondaryKey = 0;
					
					mIsCallButton = mPreferences.getBooleanGroup(Index.bool.key.remapCallButton, (mPrimaryKey + ":" + mSecondaryKey), Index.bool.value.remapCallButton);
				}
				
			} else {
				if (keyCode == mPrimaryKey) {
					if(Common.debug()) Log.d(tag, "Releasing primary key");
					
					mIsPrimaryDown = false;
					
					if (mTaps > 0 || mSecondaryKey != 0) {
						mReset = true;
					}
					
				} else if (keyCode == mSecondaryKey) {
					if(Common.debug()) Log.d(tag, "Releasing secondary key");
					
					mIsSecondaryDown = false;
					mTaps = 0;
				}
			}
		}
		
		public Boolean wasInvoked() {
			return mFinished;
		}
		
		public Boolean isDone() {
			return mFinished || mReset || mPrimaryKey == 0;
		}
		
		public Boolean isMulti() {
			return mPrimaryKey > 0 && mSecondaryKey > 0;
		}
		
		public int getTaps() {
			return mTaps;
		}
		
		public Boolean isKeyDown() {
			return mPrimaryKey > 0 && mIsPrimaryDown && (mSecondaryKey == 0 || mIsSecondaryDown);
		}
		
		public Boolean isCallButton() {
			return mIsCallButton;
		}
		
		public Boolean isDefaultLongPress() {
			return mDefaultLongPress;
		}
		
		public void setDefaultLongPress(Boolean on) {
			mDefaultLongPress = on;
		}
		
		public Integer getPrimaryKey() {
			return mPrimaryKey;
		}
		
		public Integer getSecondaryKey() {
			return mSecondaryKey;
		}
		
		public Integer getCurrentKey() {
			return mCurrentKey;
		}
	}
}
