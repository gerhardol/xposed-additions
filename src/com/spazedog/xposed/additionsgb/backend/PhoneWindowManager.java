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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.ActivityManager.RecentTaskInfo;
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
import android.os.Bundle;
import android.os.Handler;
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
import android.view.ViewConfiguration;
import android.widget.Toast;

import com.spazedog.lib.reflecttools.ReflectClass;
import com.spazedog.lib.reflecttools.ReflectClass.OnErrorListener;
import com.spazedog.lib.reflecttools.ReflectClass.OnReceiverListener;
import com.spazedog.lib.reflecttools.ReflectConstructor;
import com.spazedog.lib.reflecttools.ReflectField;
import com.spazedog.lib.reflecttools.ReflectMethod;
import com.spazedog.lib.reflecttools.utils.ReflectException;
import com.spazedog.lib.reflecttools.utils.ReflectMember;
import com.spazedog.lib.reflecttools.utils.ReflectMember.Match;
import com.spazedog.xposed.additionsgb.Common;
import com.spazedog.xposed.additionsgb.Common.Index;
import com.spazedog.xposed.additionsgb.backend.service.XServiceManager;
import com.spazedog.xposed.additionsgb.backend.service.XServiceManager.XServiceBroadcastListener;

import de.robv.android.xposed.XC_MethodHook;

public class PhoneWindowManager {
	public static final String TAG = PhoneWindowManager.class.getName();
	
	public static void init() {
		try {
			if(Common.DEBUG) Log.d(TAG, "Adding Window Manager Hook");
			
			PhoneWindowManager hooks = new PhoneWindowManager();
			ReflectClass pwm = ReflectClass.forName("com.android.internal.policy.impl.PhoneWindowManager");
			
			try {
				pwm.inject(hooks.hook_constructor);
				pwm.inject("init", hooks.hook_init);
				pwm.inject("interceptKeyBeforeQueueing", hooks.hook_interceptKeyBeforeQueueing);
				pwm.inject("interceptKeyBeforeDispatching", hooks.hook_interceptKeyBeforeDispatching);
				pwm.inject("performHapticFeedbackLw", hooks.hook_performHapticFeedbackLw);
				
			} catch (ReflectException ei) {
				Log.e(TAG, ei.getMessage(), ei);
				
				pwm.removeInjections();
			}

		} catch (ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}
	
	protected final static Boolean SDK_NEW_POWER_MANAGER = android.os.Build.VERSION.SDK_INT >= 17;
	protected final static Boolean SDK_NEW_PHONE_WINDOW_MANAGER = android.os.Build.VERSION.SDK_INT >= 11;
	protected final static Boolean SDK_NEW_RECENT_APPS_DIALOG = android.os.Build.VERSION.SDK_INT >= 11;
	protected final static Boolean SDK_NEW_CHARACTERMAP = android.os.Build.VERSION.SDK_INT >= 11;
	protected final static Boolean SDK_NEW_VIEWCONFIGURATION = android.os.Build.VERSION.SDK_INT >= 12;
	protected final static Boolean SDK_HAS_HARDWARE_INPUT_MANAGER = android.os.Build.VERSION.SDK_INT >= 16;
	protected final static Boolean SDK_HAS_MULTI_USER = android.os.Build.VERSION.SDK_INT >= 17;
	protected final static Boolean SDK_HAS_KEYGUARD_DELEGATE = android.os.Build.VERSION.SDK_INT >= 19;
	protected final static Boolean SDK_HAS_ROTATION_TOOLS = android.os.Build.VERSION.SDK_INT >= 11;

	protected static int ACTION_PASS_QUEUEING;
	protected static int ACTION_DISABLE_QUEUEING;
	
	protected static Object ACTION_PASS_DISPATCHING;
	protected static Object ACTION_DISABLE_DISPATCHING;
	
	protected static int FLAG_INJECTED;
	protected static int FLAG_VIRTUAL;
	
	protected static int INJECT_INPUT_EVENT_MODE_ASYNC;
	
	/*
	 * Android uses positive, we use negative
	 */
	protected static final int HAPTIC_VIRTUAL_KEY = (0 - (HapticFeedbackConstants.VIRTUAL_KEY + 1));
	protected static final int HAPTIC_LONG_PRESS = (0 - (HapticFeedbackConstants.LONG_PRESS + 1));
	
	/*
	 * A hack to indicate when a key is being processed by one of the original methods. 
	 */
	protected int mOriginalLocks = 0;
	
	protected Context mContext;
	protected XServiceManager mPreferences;
	
	protected Handler mHandler;
	
	protected ReflectClass mPowerManager;				// android.os.PowerManager
	protected ReflectClass mPowerManagerService;		// android.os.IPowerManager (com.android.server.power.PowerManagerService)
	protected ReflectClass mWindowManagerService;		// android.view.IWindowManager (com.android.server.wm.WindowManagerService)
	protected ReflectClass mPhoneWindowManager;			// com.android.internal.policy.impl.PhoneWindowManager
	protected ReflectClass mInputManager;				// android.hardware.input.InputManager
	protected ReflectClass mActivityManager;			// android.app.ActivityManager
	protected ReflectClass mActivityManagerService;		// android.app.IActivityManager (android.app.ActivityManagerNative)
	protected ReflectClass mAudioManager;
	protected ReflectClass mKeyguardMediator;			// com.android.internal.policy.impl.keyguard.KeyguardServiceDelegate or com.android.internal.policy.impl.KeyguardViewMediator
	protected ReflectClass mRecentApplicationsDialog;	// com.android.internal.policy.impl.RecentApplicationsDialog or com.android.internal.statusbar.IStatusBarService
	
	protected boolean mReady = false;
	
	protected boolean mInterceptKeyCode = false;
	
	protected KeyFlags mKeyFlags = new KeyFlags();
	protected KeyConfig mKeyConfig = new KeyConfig();
	
	protected Object mLockQueueing = new Object();
	
	protected Boolean mWasScreenOn = true;
	
	protected Intent mTorchIntent;
	
	protected WakeLock mWakelock;
	
	protected Map<String, ReflectConstructor> mConstructors = new HashMap<String, ReflectConstructor>();
	protected Map<String, ReflectMethod> mMethods = new HashMap<String, ReflectMethod>();
	protected Map<String, ReflectField> mFields = new HashMap<String, ReflectField>();
	
	protected Map<Integer, Boolean> mDeviceKeys = new HashMap<Integer, Boolean>();
	
	protected void registerMembers() {
		try {
			/*
			 * This does not exists in all Gingerbread versions
			 */
			mMethods.put("takeScreenshot", mPhoneWindowManager.findMethodDeep("takeScreenshot"));
			
		} catch (ReflectException e) {}
		
		mMethods.put("showGlobalActionsDialog", mPhoneWindowManager.findMethodDeep("showGlobalActionsDialog")); 
		mMethods.put("performHapticFeedback", mPhoneWindowManager.findMethodDeep("performHapticFeedbackLw", Match.BEST, "android.view.WindowManagerPolicy$WindowState", Integer.TYPE, Boolean.TYPE));
		mMethods.put("forceStopPackage", mActivityManagerService.findMethodDeep("forceStopPackage", Match.BEST, SDK_HAS_MULTI_USER ? new Object[]{String.class, Integer.TYPE} : new Object[]{String.class})); 
		mMethods.put("closeSystemDialogs", mActivityManagerService.findMethodDeep("closeSystemDialogs", Match.BEST, String.class)); 
			
		/*
		 * I really don't get Google's naming schema. 'isShowingAndNotHidden' ??? 
		 * Either it is showing or it is hidden, it cannot be both.
		 */
		mMethods.put("KeyguardMediator.isShowing", mKeyguardMediator.findMethodDeep("isShowingAndNotHidden"));
		mMethods.put("KeyguardMediator.isLocked", mKeyguardMediator.findMethodDeep("isShowing"));
		mMethods.put("KeyguardMediator.isRestricted", mKeyguardMediator.findMethodDeep("isInputRestricted"));
		mMethods.put("KeyguardMediator.dismiss", mKeyguardMediator.findMethodDeep("keyguardDone", Match.BEST, Boolean.TYPE, Boolean.TYPE));
					
		mMethods.put("toggleRecentApps", mRecentApplicationsDialog.findMethodDeep( SDK_NEW_RECENT_APPS_DIALOG ? "toggleRecentApps" : "show" ));

		if (SDK_HAS_HARDWARE_INPUT_MANAGER) {
			mMethods.put("injectInputEvent", mInputManager.findMethodDeep("injectInputEvent", Match.BEST, KeyEvent.class, Integer.TYPE));
		
		} else {
			mMethods.put("injectInputEvent", mWindowManagerService.findMethodDeep("injectInputEventNoWait", Match.BEST, KeyEvent.class));
		}

		mMethods.put("getRotation", mWindowManagerService.findMethodDeep("getRotation"));
		if (SDK_HAS_ROTATION_TOOLS) {
			mMethods.put("freezeRotation", mWindowManagerService.findMethodDeep("freezeRotation", Match.BEST, Integer.TYPE));
			mMethods.put("thawRotation", mWindowManagerService.findMethodDeep("thawRotation"));
		}

		if (SDK_HAS_MULTI_USER) {
			ReflectClass context = new ReflectClass(mContext);
			
			mConstructors.put("UserHandle", ReflectClass.forName("android.os.UserHandle").findConstructor(Match.BEST, Integer.TYPE));
			mFields.put("UserHandle.current", ReflectClass.forName("android.os.UserHandle").findField("USER_CURRENT"));
			mMethods.put("startActivityAsUser", context.findMethodDeep("startActivityAsUser", Match.BEST, Intent.class, "android.os.UserHandle"));
			mMethods.put("sendBroadcastAsUser", context.findMethodDeep("sendBroadcastAsUser", Match.BEST, Intent.class, "android.os.UserHandle"));
		}

		if (!SDK_NEW_POWER_MANAGER) {
			mMethods.put("forceUserActivityLocked", mPowerManagerService.findMethodDeep("forceUserActivityLocked"));
		}
	}
	
	protected XC_MethodHook hook_viewConfigTimeouts = new XC_MethodHook() {
		@Override
		protected final void afterHookedMethod(final MethodHookParam param) {
			if ((mKeyFlags.isKeyDown() && !mKeyFlags.wasCanceled()) || mOriginalLocks > 0) {
				param.setResult(10);
			}
		}
	};
	
	protected XC_MethodHook hook_constructor = new XC_MethodHook() {
		@Override
		protected final void afterHookedMethod(final MethodHookParam param) {
			try {
				if(Common.debug()) Log.d(TAG, "Handling construct of the Window Manager instance");
	
				ReflectClass wmp = ReflectClass.forName("android.view.WindowManagerPolicy");
				
				FLAG_INJECTED = (Integer) wmp.findField("FLAG_INJECTED").getValue();
				FLAG_VIRTUAL = (Integer) wmp.findField("FLAG_VIRTUAL").getValue();
				
				ACTION_PASS_QUEUEING = (Integer) wmp.findField("ACTION_PASS_TO_USER").getValue();
				ACTION_DISABLE_QUEUEING = 0;
				
				ACTION_PASS_DISPATCHING = SDK_NEW_PHONE_WINDOW_MANAGER ? 0 : false;
				ACTION_DISABLE_DISPATCHING = SDK_NEW_PHONE_WINDOW_MANAGER ? -1 : true;
						
				if (SDK_HAS_HARDWARE_INPUT_MANAGER) {
					INJECT_INPUT_EVENT_MODE_ASYNC = (Integer) ReflectClass.forName("android.hardware.input.InputManager").findField("INJECT_INPUT_EVENT_MODE_ASYNC").getValue();
				}
				
			} catch (ReflectException e) {
				Log.e(TAG, e.getMessage(), e);
				
				ReflectClass.forName("com.android.internal.policy.impl.PhoneWindowManager").removeInjections();
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
				ReflectClass torchConstants = ReflectClass.forName("com.android.internal.util.cm.TorchConstants");
				mTorchIntent = new Intent((String) torchConstants.findField("ACTION_TOGGLE_STATE").getValue());
				
			} catch (ReflectException er) {
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
		@TargetApi(Build.VERSION_CODES.HONEYCOMB)
		@Override
		protected final void afterHookedMethod(final MethodHookParam param) {
			try {
				mContext = (Context) param.args[0];
				mPowerManager = new ReflectClass(mContext.getSystemService(Context.POWER_SERVICE));
				mPowerManagerService = mPowerManager.findField("mService").getValueToInstance();
				mWindowManagerService = new ReflectClass(param.args[1]);
				mPhoneWindowManager = new ReflectClass(param.thisObject);
				mActivityManager = new ReflectClass(mContext.getSystemService(Context.ACTIVITY_SERVICE));
				mAudioManager = new ReflectClass(mContext.getSystemService(Context.AUDIO_SERVICE));
				mWakelock = ((PowerManager) mPowerManager.getReceiver()).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HookedPhoneWindowManager");
				mHandler = new Handler();
				
				mPreferences = XServiceManager.getInstance();
				
				if (mPreferences == null) {
					throw new ReflectException("XService has not been started", null);
				}
				
				mPreferences.addBroadcastListener(new XServiceBroadcastListener(){
					@Override
					public void onBroadcastReceive(String action, Bundle data) {
						if (action.equals("keyIntercepter:enable")) {
							mInterceptKeyCode = true;
							
						} else if (action.equals("keyIntercepter:disable")) {
							mInterceptKeyCode = false;
						}
					}
				});
				
				mContext.registerReceiver(
					new BroadcastReceiver() {
						@Override
						public void onReceive(Context context, Intent intent) {
							try {
								/*
								 * It is also best to wait with this one
								 */
								mActivityManagerService = ReflectClass.forName("android.app.ActivityManagerNative").findMethod("getDefault").invokeToInstance();
								
								if (SDK_HAS_HARDWARE_INPUT_MANAGER) {
									/*
									 * This cannot be placed in hook_init because it is to soon to call InputManager#getInstance.
									 * If we do, we will get a broken IBinder which will crash both this module along
									 * with anything else trying to access the InputManager methods.
									 */
									mInputManager = ReflectClass.forName("android.hardware.input.InputManager").findMethod("getInstance").invokeForReceiver();
								}
								
								if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
									/*
									 * This could take awhile depending on the amount of apps installed. 
									 * We use a separate thread instead of the handler to avoid blocking any key events. 
									 */
									locateTorchApps.start();
								}
								
								mKeyguardMediator = mPhoneWindowManager.findField( SDK_HAS_KEYGUARD_DELEGATE ? "mKeyguardDelegate" : "mKeyguardMediator" ).getValueToInstance();
								mKeyguardMediator.setOnErrorListener(new OnErrorListener(){
									@Override
									public void onError(ReflectMember<?> member) {
										member.getReflectClass().setReceiver(
												mPhoneWindowManager.findField( SDK_HAS_KEYGUARD_DELEGATE ? "mKeyguardDelegate" : "mKeyguardMediator" ).getValue()
										);
									}
								});
								
								mRecentApplicationsDialog = ReflectClass.forName( SDK_NEW_RECENT_APPS_DIALOG ? "com.android.internal.statusbar.IStatusBarService" : "com.android.internal.policy.impl.RecentApplicationsDialog" );
								mRecentApplicationsDialog.setOnReceiverListener(new OnReceiverListener(){
									@Override
									public Object onReceiver(ReflectMember<?> member) {
										Object recentAppsService;
										
										if (SDK_NEW_RECENT_APPS_DIALOG) {
											recentAppsService = member.getReflectClass().bindInterface("statusbar").getReceiver();
											
										} else {
											recentAppsService = member.getReflectClass().newInstance(mContext);
										}
										
										member.getReflectClass().setReceiver(recentAppsService);
										
										return recentAppsService;
									}
								});
								mRecentApplicationsDialog.setOnErrorListener(new OnErrorListener(){
									@Override
									public void onError(ReflectMember<?> member) {
										member.getReflectClass().setReceiver(null);
									}
								});
								
								registerMembers();
								
								mReady = true;
								mContext.unregisterReceiver(this);
								
								try {
									/*
									 * We hook this class here because we don't want it to affect the whole system.
									 * Also we need to control when and when not to change the return value.
									 */
									ReflectClass wc = ReflectClass.forName("android.view.ViewConfiguration");

									wc.inject("getLongPressTimeout", hook_viewConfigTimeouts);
									wc.inject("getGlobalActionKeyTimeout", hook_viewConfigTimeouts);
									
								} catch (ReflectException e) {
									Log.e(TAG, e.getMessage(), e);
								}
								
							} catch (ReflectException e) {
								Log.e(TAG, e.getMessage(), e);
								
								mPhoneWindowManager.removeInjections();
							}
						}
					}, new IntentFilter("android.intent.action.BOOT_COMPLETED")
				);
				
			} catch (ReflectException e) {
				Log.e(TAG, e.getMessage(), e);
				
				ReflectClass.forName("com.android.internal.policy.impl.PhoneWindowManager").removeInjections();
			}
		}
	};
	
	private static int shortTime(){
		return (int)(SystemClock.uptimeMillis() % 10000);
	}
	
	/**
	 * ========================================================================================
	 * Gingerbread uses arguments interceptKeyBeforeQueueing(Long whenNanos, Integer action, Integer flags, Integer keyCode, Integer scanCode, Integer policyFlags, Boolean isScreenOn)
	 * ICS/JellyBean uses arguments interceptKeyBeforeQueueing(KeyEvent event, Integer policyFlags, Boolean isScreenOn)
	 */
	protected XC_MethodHook hook_interceptKeyBeforeQueueing = new XC_MethodHook() {
		
		protected Boolean mIsOriginalLocked = false;
		
		@TargetApi(Build.VERSION_CODES.HONEYCOMB)
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
			final int policyIndex = SDK_NEW_PHONE_WINDOW_MANAGER ? 1 : 5;
			final int policyFlags = (Integer) (param.args[policyIndex]);
			final int keyCode = (Integer) (!SDK_NEW_PHONE_WINDOW_MANAGER ? param.args[3] : ((KeyEvent) param.args[0]).getKeyCode());
			final int repeatCount = (Integer) (!SDK_NEW_PHONE_WINDOW_MANAGER ? 0 : ((KeyEvent) param.args[0]).getRepeatCount());
			final boolean isScreenOn = (Boolean) (!SDK_NEW_PHONE_WINDOW_MANAGER ? param.args[6] : param.args[2]);
			final boolean down = action == KeyEvent.ACTION_DOWN;
			final long downTime = SDK_NEW_PHONE_WINDOW_MANAGER ? (long) ((KeyEvent) param.args[0]).getDownTime() : SystemClock.uptimeMillis();
			
			String tag = TAG + "#Queueing/" + (down ? "Down " : "Up ") + keyCode + ":" + shortTime() + "(" + mKeyFlags.getTaps() + "," + repeatCount+ "):" ;

			/*
			 * Using KitKat work-around from the InputManager Hook
			 */
			final boolean isInjected = SDK_HAS_HARDWARE_INPUT_MANAGER ? 
					(((KeyEvent) param.args[0]).getFlags() & FLAG_INJECTED) != 0 : (policyFlags & FLAG_INJECTED) != 0;
			
			if (isInjected) {
				if (down && repeatCount > 0) {
					if(Common.debug()) Log.d(tag, "Injected repeated key, no event change");
					
					/*
					 * Normally repeated events will not continue to invoke this method. 
					 * But it seams that repeating an event using injection will. On most devices
					 * the original methods themselves seams to be handling this just fine, but a few 
					 * stock ROM's are treating these as both new and repeated events. 
					 */
					param.setResult(ACTION_PASS_QUEUEING);
					
				} else {
					param.args[policyIndex] = policyFlags & ~FLAG_INJECTED;
				}
				
				synchronized(hook_performHapticFeedbackLw) {
					mIsOriginalLocked = true;
					mOriginalLocks += 1;
				}
			
				return;
			}
			
			synchronized(mLockQueueing) {
				if(Common.debug()) Log.d(tag, (down ? "Starting" : "Stopping") + " event");
				
				if (!internalKey(keyCode)) {
					if (mInterceptKeyCode) {
						param.setResult(ACTION_DISABLE_QUEUEING);
					}

					mKeyFlags.cancel();
					
					return;
					
				} else if (down && isScreenOn != mWasScreenOn) {
					mWasScreenOn = isScreenOn;
					
					mKeyFlags.reset();
				}
				
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
					performHapticFeedback(HAPTIC_VIRTUAL_KEY);
				}
				
				int ongoingKeys[] = mKeyFlags.getOngoingKeyCodes();
				int specialKey = mKeyFlags.getSpecialKey();
				boolean newAction = mKeyFlags.registerKey(keyCode, down, downTime);
				
				if (newAction) {
					
					if (ongoingKeys[0] > 0) {
						//The key should have been released when up received, but another key pushed and aborted
						if(Common.debug()) Log.d(tag, "Releasing long press event for " + ongoingKeys[0]);
						
						injectInputEvent(ongoingKeys[0], 0, -1, false, true);
						if(ongoingKeys[1] > 0) {
							injectInputEvent(ongoingKeys[1], 0, -1, false, true);								
						}
					} else if (specialKey > 0) {
						if(Common.debug()) Log.d(tag, "Short press for special key long press event" + specialKey);
						
						injectInputEvent(specialKey, mKeyFlags.firstDownTime(), 0, false, true);
					}
				}
				
				if (isScreenOn && mInterceptKeyCode) {
					if (down) {
						if(Common.debug()) Log.d(tag, "Intercepting key code");
						
						Bundle bundle = new Bundle();
						bundle.putInt("keyCode", keyCode);
						
						mPreferences.sendBroadcast("keyIntercepter:keyCode", bundle);
						
						mKeyFlags.cancel();
					}
					
					param.setResult(ACTION_DISABLE_QUEUEING);
					
				} else {

					if (newAction) {
						if(Common.debug()) Log.d(tag, "Configuring new event");

						mWasScreenOn = isScreenOn;

						mKeyConfig.newAction(mKeyFlags, isScreenOn);

						if (!isScreenOn) {
							if (!mWakelock.isHeld()) {
								mWakelock.acquire(3000);
							}

							pokeUserActivity(false);
						}
					} 
					else if (!mKeyConfig.hasMoreAction((down ? ActionTypes.press : ActionTypes.tap), mKeyFlags, false))
					{
						mKeyFlags.reset();
					}
					if(Common.debug()) Log.d(tag, "Passing event to dispatcher");
					
					param.setResult(ACTION_PASS_QUEUEING);
				}
			}
		}
		
		@Override
		protected final void afterHookedMethod(final MethodHookParam param) {
			if (mIsOriginalLocked) {
				mIsOriginalLocked = false;
				
				/*
				 * We don't use "-= 1" to avoid it going below 0. 
				 * It should only be 0, 1 or 2
				 */
				synchronized(hook_performHapticFeedbackLw) {
					mOriginalLocks = mOriginalLocks > 1 ? 1 : 0;
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
		
		protected Boolean mIsOriginalLocked = false;
		
		@SuppressLint("NewApi") @Override
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
			final int policyIndex = SDK_NEW_PHONE_WINDOW_MANAGER ? 2 : 7;
			final int policyFlags = (Integer) (param.args[policyIndex]);
			//final int eventFlags = (Integer) (!SDK_NEW_PHONE_WINDOW_MANAGER ? param.args[2] : ((KeyEvent) param.args[1]).getFlags());
			final int repeatCount = (Integer) (!SDK_NEW_PHONE_WINDOW_MANAGER ? param.args[6] : ((KeyEvent) param.args[1]).getRepeatCount());
			//final long eventTime = SDK_NEW_PHONE_WINDOW_MANAGER ? (long) ((KeyEvent) param.args[1]).getEventTime() : SystemClock.uptimeMillis();
			final boolean down = action == KeyEvent.ACTION_DOWN;

			int extraFlags = 0;
			String tag = TAG + "#Dispatch/" + (down ? "Down " : "Up ") + keyCode + ":" + shortTime() + "(" + mKeyFlags.getTaps() + "," + repeatCount+ "):" ;
			
			//Skipped not tracked key, except Power that need handling
			if (!mKeyConfig.hasAnyAction() && keyCode != KeyEvent.KEYCODE_POWER) {
				if(Common.debug()) Log.d(tag, "No action");
				
				param.args[policyIndex] = policyFlags & ~FLAG_INJECTED;
				return;
			}

			/*
			 * Using KitKat work-around from the InputManager Hook
			 */
			final boolean isInjected = SDK_HAS_HARDWARE_INPUT_MANAGER ? 
					(((KeyEvent) param.args[1]).getFlags() & FLAG_INJECTED) != 0 : (policyFlags & FLAG_INJECTED) != 0;

			if (isInjected) {
				if (down && mKeyFlags.isKeyDown() && mKeyFlags.isOngoingKeyCode(keyCode)) { 

					//Limit rate for key repeat
					//It seems natural to pass first down event immediately but then first is longpress
					//(normally second event should be longpress)
					KeyFlags wasFlags = null;
					if(Common.debug()) Log.d(tag, "Delay long press key repeat "+((long) SystemClock.uptimeMillis() - mKeyFlags.currDown())+" ");

					wasFlags = mKeyFlags.CloneFlags();
					{ //wait block
						Integer curDelay = 0;
						final int longLongPressDelay = 2; //Wait 2 times normal long press
						final Integer keyDelay = (!mKeyFlags.isOngoingLongPress()) ? 
								//start key repeat and long press timeout are the same by default, not using ViewConfiguration.getKeyRepeatTimeout()
								longLongPressDelay * mKeyConfig.getLongPressDelay() :
									(SDK_NEW_VIEWCONFIGURATION ? ViewConfiguration.getKeyRepeatDelay() : 50);

						do {
							final Integer t = 10;
							try {
								Thread.sleep(t);

							} catch (Throwable e) {}

							curDelay += t;
						} while (mKeyFlags.SameFlags(wasFlags) && curDelay < keyDelay);
					}
					
					synchronized(mLockQueueing) {
						if(wasFlags == null || mKeyFlags.SameFlags(wasFlags)) {
							if(Common.debug()) Log.d(tag, "Long press key repeat "+((long) SystemClock.uptimeMillis() - mKeyFlags.currDown()));
							
							if (!mKeyFlags.isOngoingLongPress()) {
								//The second down should be long press
								if(Common.debug()) Log.d(tag, "Setting long press on the mapped key:" + keyCode);

								mKeyFlags.setOngoingLongPress(true);
								extraFlags |= KeyEvent.FLAG_LONG_PRESS;
							}

							injectInputEvent(keyCode, mKeyFlags.firstDownTime(), repeatCount+1, false, false);
						}
					}
					//TODO: Need to dispatch default event, for tracking?
				}
				
				synchronized(hook_performHapticFeedbackLw) {
					mIsOriginalLocked = true;
					mOriginalLocks += 1;
				}
				
				param.args[policyIndex] = policyFlags & ~FLAG_INJECTED | extraFlags;
			
				return;
				
			} else if (!internalKey(keyCode)) {
				return;
				
			} else if (mKeyFlags.getSpecialKey() > 0) {
				if(Common.debug()) Log.d(tag, "Short press for special key long press event: " + mKeyFlags.getSpecialKey());
				
				synchronized(mLockQueueing) {
					injectInputEvent(mKeyFlags.getSpecialKey(), mKeyFlags.firstDownTime(), 0, false, true);
					mKeyFlags.setSpecialKey(0);
				}
			} else if (!down && mKeyFlags.isOngoingKeyCode()) {
				if(Common.debug()) Log.d(tag, "Releasing long press event");
				
				synchronized(mLockQueueing) {
					injectInputEvent(mKeyFlags.getOngoingKeyCodes()[0], 0, -1, false, true);
					if(mKeyFlags.getOngoingKeyCodes()[1] > 0) {
						injectInputEvent(mKeyFlags.getOngoingKeyCodes()[1], 0, -1, false, true);
					}

					mKeyFlags.setOngoingKeyCode(0, 0);
					mKeyFlags.setOngoingLongPress(false);
				}
				
			} else if (!mKeyFlags.wasInvoked()) {
				//if(Common.debug()) Log.d(tag, (down ? "Starting" : "Stopping") + " event");
				//This check complicated to detect double (and triple) clicks directly at down
				//when no other event is configured for long press and no other event follows
				//This is to get same behavior as original, where double-tap always was detected at down
				
				if (down && (mKeyFlags.getTaps() <= 1 || mKeyConfig.hasAction(ActionTypes.press, mKeyFlags))) {
					if (Common.debug()) Log.d(tag, "Waiting for long press timeout");
					
					KeyFlags wasFlags = mKeyFlags.CloneFlags();
					final Integer pressDelay = mKeyConfig.getLongPressDelay();
					{// wait block
						Integer curDelay = 0;

						do {
							final Integer t = 10;
							try {
								Thread.sleep(t);

							} catch (Throwable e) {}

							curDelay += t;

						} while (mKeyFlags.SameFlags(wasFlags) && curDelay < pressDelay);
					}
					
					synchronized(mLockQueueing) {
						if (mKeyFlags.SameFlags(wasFlags)) {
							performHapticFeedback(HAPTIC_LONG_PRESS);
							
							mKeyFlags.finish();
							String keyAction = mKeyConfig.getAction(ActionTypes.press, mKeyFlags);

							if (mKeyConfig.isAction(keyAction)) {
								if (Common.debug()) Log.d(tag, "Invoking mapped long press action: " + keyAction);
								int code = mKeyConfig.getEventKeyCode(keyAction, keyCode);

								if (code == KeyEvent.KEYCODE_POWER) {
									//fix special handling for Power, sending first event when releasing
									mKeyFlags.setSpecialKey(code);
								} else {
									handleKeyAction(keyAction, code, mKeyFlags.firstDownTime(), false);
									mKeyFlags.setOngoingKeyCode(code, 0);
								}

							} else if (mKeyFlags.getTaps() > 1) {
								//Check not needed while the "early" tap detection skips press
								if(Common.debug()) Log.d(tag, "No default long press action for press: " + mKeyFlags.getTaps());
								
							} else {
								if(Common.debug()) Log.d(tag, "Invoking default long press action: " + keyCode);
								
								mKeyFlags.setOngoingLongPress(true);
								
								injectInputEvent(mKeyFlags.getPrimaryKey(), mKeyFlags.firstDownTime(), 0, true, false);
								if(mKeyFlags.getSecondaryKey()>0) {
									injectInputEvent(mKeyFlags.getSecondaryKey(), mKeyFlags.firstDownTime(), 0, true, false);
								}
								mKeyFlags.setOngoingKeyCode(mKeyFlags.getPrimaryKey(), mKeyFlags.getSecondaryKey());
								
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
					
					if (mKeyFlags.getSpecialKey() > 0) {
						int curDelay = 0;

						do {
							final Integer t = 10;
							try {
								Thread.sleep(t);
							} catch (Throwable e) {}

							curDelay += t;

						} while (mKeyFlags.SameFlags(wasFlags) && curDelay < 2* pressDelay);

						synchronized(mLockQueueing) {
							if (mKeyFlags.SameFlags(wasFlags)&& curDelay >= 2* pressDelay) {
								if(Common.debug()) Log.d(tag, "Invoking long press for long press action: " + mKeyFlags.getSpecialKey());
								//This is a long press, inject code
								injectInputEvent(mKeyFlags.getSpecialKey(), mKeyFlags.firstDownTime(), 0, true, false);
								mKeyFlags.setOngoingKeyCode(mKeyFlags.getSpecialKey(), 0);
								mKeyFlags.setOngoingLongPress(true);
								mKeyFlags.setSpecialKey(0);
							}
						}
					}
					
				} else {
					KeyFlags wasFlags = null;
					
					//timeout if there are events following otherwise direct action (or default first)
					if (mKeyConfig.hasMoreAction(ActionTypes.tap, mKeyFlags, true)) {
						if(Common.debug()) Log.d(tag, "Waiting for tap timeout");
						
						int curDelay = 0;
						final int tapDelay = mKeyConfig.getDoubleTapDelay();
						wasFlags = mKeyFlags.CloneFlags();
						do {
							final Integer t = 10;
							try {
								Thread.sleep(t);
								
							} catch (Throwable e) {}
							
							curDelay += t;
							
						} while (mKeyFlags.SameFlags(wasFlags) && curDelay < tapDelay);
					}
					
					synchronized(mLockQueueing) {
						if ((wasFlags == null || mKeyFlags.SameFlags(wasFlags)) && mKeyFlags.getCurrentKey() == keyCode) {

							mKeyFlags.finish();
							int callCode = 0;
							
							if ((mKeyFlags.getTaps() == 1) && mKeyFlags.isCallButton()) {
								int mode = ((AudioManager) mAudioManager.getReceiver()).getMode();

								if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
									callCode = KeyEvent.KEYCODE_ENDCALL;

								} else if (mode == AudioManager.MODE_RINGTONE) {
									callCode = KeyEvent.KEYCODE_CALL;
								}
							}
							
							if (callCode == 0) {
								String keyAction = mKeyConfig.getAction(ActionTypes.tap, mKeyFlags);
								if (mKeyConfig.hasAction(ActionTypes.tap, mKeyFlags)) {
									if(Common.debug()) Log.d(tag, "Invoking click action: " + keyAction);
								
									int code = mKeyConfig.getEventKeyCode(keyAction, keyCode);
									handleKeyAction(keyAction, code, mKeyFlags.firstDownTime(), true);
									
								} else if (mKeyFlags.getTaps() > 1) {
									if(Common.debug()) Log.d(tag, "No mapped click action" + mKeyFlags.getTaps());
									
								} else {
									//insert separately here, could probably use injectInputEvent()
									handleKeyAction(keyAction, mKeyFlags.getPrimaryKey(), mKeyFlags.firstDownTime(), true);
									if (mKeyFlags.getSecondaryKey() > 0) {
										handleKeyAction(keyAction, mKeyFlags.getSecondaryKey(), mKeyFlags.firstDownTime(), true);
									}
								}
								
							} else {
								if(Common.debug()) Log.d(tag, "Invoking call button");
								
								injectInputEvent(callCode, mKeyFlags.firstDownTime(), 0, false, true);
							}
						}
					}
				}
			}
			
			param.setResult(ACTION_DISABLE_DISPATCHING);
		}
		
		
		@Override
		protected final void afterHookedMethod(final MethodHookParam param) {
			if (mIsOriginalLocked) {
				mIsOriginalLocked = false;
				
				/*
				 * We don't use "-= 1" to avoid it going below 0. 
				 * It should only be 0, 1 or 2
				 */
				synchronized(hook_performHapticFeedbackLw) {
					mOriginalLocks = mOriginalLocks > 1 ? 1 : 0;
				}
			}
		}
	};
	
	protected XC_MethodHook hook_performHapticFeedbackLw = new XC_MethodHook() {
		@Override
		protected final void beforeHookedMethod(final MethodHookParam param) {
			/*
			 * This is used to avoid having the original methods create feedback on our handled keys.
			 * Some Stock ROM's like TouchWiz often does this, even on injected keys. 
			 */
			switch ( (Integer) param.args[1] ) {
				/*
				 * Makes sure that we never disable our own feedback calls
				 */
				case HAPTIC_VIRTUAL_KEY: param.args[1] = HapticFeedbackConstants.VIRTUAL_KEY; return; 
				case HAPTIC_LONG_PRESS: param.args[1] = HapticFeedbackConstants.LONG_PRESS; return;
				
				/*
				 * We can't disable original calls completely. 
				 * The key handling methods are not the only once using this.
				 * Lock screen pattern feedback and others use it as well. 
				 */
				default:
					if (mOriginalLocks > 0) {
						param.setResult(true);
					}
			}
		}
	};
	
	protected Boolean internalKey(Integer keyCode) {
		if (!mDeviceKeys.containsKey(keyCode)) {
			mDeviceKeys.put(keyCode, KeyCharacterMap.deviceHasKey(keyCode));
		}
		
		return mDeviceKeys.get(keyCode) || mPreferences.getBoolean(Common.Index.bool.key.remapAllowExternals, Common.Index.bool.value.remapAllowExternals);
	}
	
	@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
	public void pokeUserActivity(Boolean forced) {
		if (forced) {
			if (SDK_NEW_POWER_MANAGER) {
				((PowerManager) mPowerManager.getReceiver()).wakeUp(SystemClock.uptimeMillis());
				
			} else {
				/*
				 * API's below 17 does not support PowerManager#wakeUp, so
				 * instead we will trick our way into the hidden IPowerManager#forceUserActivityLocked which 
				 * is not accessible trough the regular PowerManager class. It the same method that 
				 * turns on the screen when you plug in your USB cable.
				 */
				try {
					mMethods.get("forceUserActivityLocked").invoke();
					
				} catch (ReflectException e) {
					Log.e(TAG, e.getMessage(), e);
				}
			}
			
		} else {
			((PowerManager) mPowerManager.getReceiver()).userActivity(SystemClock.uptimeMillis(), true);
		}
	}

	@SuppressLint("NewApi")
	protected void changeDisplayState(Boolean on) {
		if (on) {
			pokeUserActivity(true);
			
		} else {
			((PowerManager) mPowerManager.getReceiver()).goToSleep(SystemClock.uptimeMillis());
		}
	}
	
	@SuppressLint("NewApi")
	protected void injectInputEvent(final int keyCode, final long time, final int repeatDown, final boolean longpress, final boolean up) {
		synchronized(PhoneWindowManager.class) {
			long now = SystemClock.uptimeMillis();
			int characterMap = SDK_NEW_CHARACTERMAP ? KeyCharacterMap.VIRTUAL_KEYBOARD : 0;
			
			int flags = longpress ? KeyEvent.FLAG_LONG_PRESS|KeyEvent.FLAG_FROM_SYSTEM|FLAG_INJECTED : KeyEvent.FLAG_FROM_SYSTEM|FLAG_INJECTED;

			//Note: longpress and up should not be set simultaneously
			try {
				if(repeatDown >= 0) {
					KeyEvent event = new KeyEvent(time, now, KeyEvent.ACTION_DOWN, keyCode, repeatDown, 0, characterMap, 0, flags, InputDevice.SOURCE_KEYBOARD);
					if (SDK_HAS_HARDWARE_INPUT_MANAGER) {
						mMethods.get("injectInputEvent").invoke(event, INJECT_INPUT_EVENT_MODE_ASYNC);

					} else {
						mMethods.get("injectInputEvent").invoke(event);
					}
				}
				
				if (up) {
					KeyEvent event = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0, characterMap, 0, flags, InputDevice.SOURCE_KEYBOARD);
					if (SDK_HAS_HARDWARE_INPUT_MANAGER) {
						mMethods.get("injectInputEvent").invoke(KeyEvent.changeAction(event, KeyEvent.ACTION_UP), INJECT_INPUT_EVENT_MODE_ASYNC);
						
					} else {
						mMethods.get("injectInputEvent").invoke(KeyEvent.changeAction(event, KeyEvent.ACTION_UP));
					}
				}
				
			} catch (ReflectException e) {
				Log.e(TAG, e.getMessage(), e);
			}
		}
	}

	protected void performHapticFeedback(Integer effectId) {
		try {
			mMethods.get("performHapticFeedback").invoke(null, effectId, false);
			
		} catch (ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}
	
	protected Boolean isKeyguardShowing() {
		try {
			return (Boolean) mMethods.get("KeyguardMediator.isShowing").invoke();
			
		} catch (ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
		}
		
		return false;
	}
	
	protected Boolean isKeyguardLockedAndInsecure() {
		if (isKeyguardLocked()) {
			try {
				return !((Boolean) mMethods.get("KeyguardMediator.isRestricted").invoke());
				
			} catch (ReflectException e) {
				Log.e(TAG, e.getMessage(), e);
			}
		}
		
		return false;
	}
	
	protected Boolean isKeyguardLocked() {
		try {
			return (Boolean) mMethods.get("KeyguardMediator.isLocked").invoke();
			
		} catch (ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
		}
		
		return false;
	}
	
	protected void keyGuardDismiss() {
		if (isKeyguardLocked()) {
			try {
				mMethods.get("KeyguardMediator.dismiss").invoke(false, true);
				
			} catch (ReflectException e) {
				Log.e(TAG, e.getMessage(), e);
			}
		}
	}
	
	protected String getRunningPackage() {
		List<ActivityManager.RunningTaskInfo> packages = ((ActivityManager) mActivityManager.getReceiver()).getRunningTasks(1);
		
		return packages.size() > 0 ? packages.get(0).baseActivity.getPackageName() : null;
	}
	
	protected String getHomePackage() {
		Intent intent = new Intent(Intent.ACTION_MAIN);
		intent.addCategory(Intent.CATEGORY_HOME);
		ResolveInfo res = mContext.getPackageManager().resolveActivity(intent, 0);
		
		return res.activityInfo != null && !"android".equals(res.activityInfo.packageName) ? 
				res.activityInfo.packageName : "com.android.launcher";
	}
	
	protected void launchApplication(String packageName) {
		Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(packageName);
		
		if (isKeyguardLockedAndInsecure()) {
			keyGuardDismiss();
		}
		
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
		
		if (SDK_HAS_MULTI_USER) {
			try {
				Object userCurrent = mFields.get("UserHandle.current").getValue();
				Object user = mConstructors.get("UserHandle").invoke(userCurrent);
				
				mMethods.get("startActivityAsUser").invoke(intent, user);
				
			} catch (ReflectException e) {
				Log.e(TAG, e.getMessage(), e);
			}
			
		} else {
			mContext.startActivity(intent);
		}
	}
	
	protected void toggleLastApplication() {
		List<RecentTaskInfo> packages = ((ActivityManager) mActivityManager.getReceiver()).getRecentTasks(5, ActivityManager.RECENT_WITH_EXCLUDED);
		
		for (int i=1; i < packages.size(); i++) {
			String intentString = packages.get(i).baseIntent + "";
			
			int indexStart = intentString.indexOf("cmp=")+4;
		    int indexStop = intentString.indexOf("/", indexStart);
			
			String packageName = intentString.substring(indexStart, indexStop);
			
			if (!packageName.equals(getHomePackage()) && !packageName.equals("com.android.systemui")) {
				Intent intent = packages.get(i).baseIntent;
				intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
				
				mContext.startActivity(intent);
			}
		}
	}
	
	protected void sendCloseSystemWindows(String reason) {
		if(Common.debug()) Log.d(TAG, "Closing all system windows");
		
		try {
			mMethods.get("closeSystemDialogs").invoke(reason);
			
		} catch (ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}

	protected void openRecentAppsDialog() {
		if(Common.debug()) Log.d(TAG, "Invoking Recent Application Dialog");
		
		sendCloseSystemWindows("recentapps");
		
		try {
			mMethods.get("toggleRecentApps").invoke();
			
		} catch (ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}
	
	protected void openGlobalActionsDialog() {
		if(Common.debug()) Log.d(TAG, "Invoking Global Actions Dialog");
		
		sendCloseSystemWindows("globalactions");
		
		try {
			mMethods.get("showGlobalActionsDialog").invoke();
			
		} catch (ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
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
				
				try {
					mMethods.get("freezeRotation").invoke(orientation);
					
				} catch (ReflectException e) {
					Log.e(TAG, e.getMessage(), e);
				}
				
			} else {
				try {
					mMethods.get("thawRotation").invoke();
					
				} catch (ReflectException e) {
					Log.e(TAG, e.getMessage(), e);
				}
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
		try {
			return (Integer) mMethods.get("getRotation").invoke();

		} catch (ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
		}
		
		return 0;
	}
	
	protected Integer getNextRotation(Boolean backwards) {
		Integer  position = getCurrentRotation();
		
		return (position == Surface.ROTATION_90 || position == Surface.ROTATION_0) && backwards ? 270 : 
			(position == Surface.ROTATION_270 || position == Surface.ROTATION_0) && !backwards ? 90 : 0;
	}
	
	protected void killForegroundApplication() {
		if(Common.debug()) Log.d(TAG, "Start searching for foreground application to kill");
		
		List<ActivityManager.RunningTaskInfo> packages = ((ActivityManager) mActivityManager.getReceiver()).getRunningTasks(5);
		
		for (int i=0; i < packages.size(); i++) {
			String packageName = packages.get(0).baseActivity.getPackageName();
			
			if (!packageName.equals(getHomePackage()) && !packageName.equals("com.android.systemui")) {
				if(Common.debug()) Log.d(TAG, "Invoking force stop on " + packageName);
				
				try {
					if (SDK_HAS_MULTI_USER) {
						mMethods.get("forceStopPackage").invoke(packageName, mFields.get("UserHandle.current").getValue());
	
					} else {
						mMethods.get("forceStopPackage").invoke(packageName);
					}
					
				} catch (ReflectException e) {
					Log.e(TAG, e.getMessage(), e);
				}
			}
		}
	}
	
	protected void takeScreenshot() {
		try {
			mMethods.get("takeScreenshot").invoke();
			
		} catch (ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}
	
	protected void toggleFlashLight() {
		if (mTorchIntent != null) {
			if (SDK_HAS_MULTI_USER) {
				try {
					Object userCurrent = mFields.get("UserHandle.current").getValue();
					Object user = mConstructors.get("UserHandle").invoke(userCurrent);
					
					mMethods.get("sendBroadcastAsUser").invoke(mTorchIntent, user);
					
				} catch (ReflectException e) {
					Log.e(TAG, e.getMessage(), e);
				}
				
			} else {
				mContext.sendBroadcast(mTorchIntent);
			}
		}
	}
	
	protected void handleKeyAction(final String action, final Integer code, final long time, final boolean upEvent) {
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
					injectInputEvent(code, time, 0, false, upEvent);
				}
			}
		});
	}
	
	protected enum ActionTypes { press, tap };
	
	//KeyConfig contains information about the current event, related to KeyFlags
	protected class KeyConfig {
		protected static final int maxTapActions = 3*2; //ActionTypes.values().length;
		
		//actions in the order they appear: press 1, tap 1, press 2, tap 2 etc
		private String[] mActions; // new String[maxTapActions];
		private Integer mKeyDelayTap = 0;
		private Integer mKeyDelayPress = 0;
		private Boolean mAnyAction = false;
		
		public KeyConfig() {
			mActions = new String[]{null, null, null, null, null, null};
		}
		
		public void newAction(KeyFlags keyFlags, Boolean isScreenOn)
		{
			Boolean extended = mPreferences.isPackageUnlocked();
			this.mKeyDelayPress = mPreferences.getInt(Common.Index.integer.key.remapPressDelay, Common.Index.integer.value.remapPressDelay);
			if (this.mKeyDelayPress <= 0) {
				this.mKeyDelayPress = ViewConfiguration.getLongPressTimeout();
			}
			this.mKeyDelayTap = mPreferences.getInt(Common.Index.integer.key.remapTapDelay, Common.Index.integer.value.remapTapDelay);
			if (this.mKeyDelayTap == 0) {
				this.mKeyDelayTap = ViewConfiguration.getLongPressTimeout();
			} else if (this.mKeyDelayTap < 0) {
				this.mKeyDelayTap = this.mKeyDelayPress;
			}
			List<String> actions = null;

			mAnyAction = false;
			if (!mKeyFlags.isMulti() || extended) {
				String keyGroupName = mKeyFlags.getPrimaryKey() + ":" + mKeyFlags.getSecondaryKey();
				String appCondition = !isScreenOn ? null : 
					isKeyguardShowing() ? "guard" : extended ? getRunningPackage() : null;

			    actions = appCondition != null ? mPreferences.getStringArrayGroup(String.format(Index.array.groupKey.remapKeyActions_$, appCondition), keyGroupName, null) : null;

				if (actions == null) {
					actions = mPreferences.getStringArrayGroup(String.format(Index.array.groupKey.remapKeyActions_$, isScreenOn ? "on" : "off"), keyGroupName, null);
				}
			}
			
			for (int i = 0; i < maxTapActions; i++) {
				if (actions != null && i < actions.size()) {
					mActions[i] = actions.get(i);
					if (mActions[i] != null) {
						mAnyAction = true;
					}
				} else {
					mActions[i] = null;
				}
			}
		}
		
		public Boolean hasAnyAction() {
			return mAnyAction;
		}
		
		private int getIndex(ActionTypes atype, KeyFlags keyFlags) {
			int index = (keyFlags.getTaps() - 1) * 2;
			if (atype == ActionTypes.tap) {index++;}
			return index;
		}
		
		public String getAction(ActionTypes atype, KeyFlags keyFlags) {
			int index = getIndex(atype, keyFlags);
			if(index > mActions.length) {return null;}
			return mActions[index];
		}
		
		public Boolean isAction(String action) {
			return (action != null);
		}
		
		public Boolean hasAction(ActionTypes atype, KeyFlags keyFlags) {
			return isAction(getAction(atype, keyFlags));
		}
		
		public Boolean hasMoreAction(ActionTypes atype, KeyFlags keyFlags, Boolean next) {
			Boolean result = false;
			int index = getIndex(atype, keyFlags);
			if(next){index++;}
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
		
		//static get the possible keycode for the event (or default)
		public int getEventKeyCode(String action, int keyCode) {
			return (action != null && action.matches("^[0-9]+$") ? Integer.parseInt(action) : 
				(action == null ? keyCode : 0));
		}
	}
	
	//Status of keys and combined events
	protected class KeyFlags {
		private Boolean mIsPrimaryDown = false;
		private Boolean mIsSecondaryDown = false;
		private Boolean mIsAggregatedDown = false;
		private Boolean mFinished = false;
		private Boolean mReset = false;
		private Boolean mCancel = false;
		private Boolean mIsCallButton = false;
		private int[] mOngoingKeyCodes; // new int[2];
		private Boolean mLongPressIsSet = false;
		
		private Integer mTaps = 0; //Corresponds to eventCount in KeyEvent() (often mixed with repeatCount)
		private Integer mPrimaryKey = 0;
		private Integer mSecondaryKey = 0;
		private Integer mCurrentKey = 0;
		private Integer mSpecialKey = 0;
		
		private long firstDown; //time
		private long currDown;
		
		public KeyFlags() {
			mOngoingKeyCodes = new int[]{0, 0};
		}
		
		public KeyFlags CloneFlags() {
			KeyFlags k = new KeyFlags();
			k.mIsPrimaryDown = this.mIsPrimaryDown;
			k.mIsSecondaryDown = this.mIsSecondaryDown;
			k.mTaps = this.mTaps;
			k.mPrimaryKey = this.mPrimaryKey;
			k.mSecondaryKey = this.mSecondaryKey;
			return k;
		}
		public boolean SameFlags(KeyFlags o2) {
			boolean result = false;
			if (this.mIsPrimaryDown == o2.mIsPrimaryDown &&
					this.mIsSecondaryDown == o2.mIsSecondaryDown &&
					this.mTaps == o2.mTaps &&
					this.mPrimaryKey == o2.mPrimaryKey &&
					this.mSecondaryKey == o2.mSecondaryKey) {
				result = true;
			}
			return result;
		}
		public void finish() {
			mFinished = true;
		}
		
		public void cancel() {
			mCancel = true;
			mReset = true;
		}
		
		public void reset() {
			mReset = true;
		}
		
		public Boolean registerKey(Integer keyCode, Boolean down, long time) {
			Boolean newEvent = false;
			mCurrentKey = keyCode;

			String tag = TAG + "#KeyFlags:" + keyCode;

			//A new key is pressed, forget about ongoing codes
			mOngoingKeyCodes[0] = 0;
			mOngoingKeyCodes[1] = 0;

			if (down) {
				if (!isDone() && mTaps >= 1 && (keyCode == mPrimaryKey || keyCode == mSecondaryKey)) {
					if(Common.debug()) Log.d(tag, "Registring repeated event");
										
					if (!mIsPrimaryDown && !mIsSecondaryDown) {
						mTaps++;
						this.currDown = SystemClock.uptimeMillis();
					}
					if (keyCode == mSecondaryKey) {
						mIsSecondaryDown = true;
						
					} else {
						mIsPrimaryDown = true;
					}
					//Aggregated down state: Only require secondary for first tap
					if (mIsPrimaryDown && (mSecondaryKey == 0 || mIsSecondaryDown || mTaps > 1)) {
						mIsAggregatedDown = true;
					}
				} else if (!isDone() && mTaps == 1 && mPrimaryKey > 0 && mIsPrimaryDown && keyCode != mPrimaryKey && (mSecondaryKey == 0 || mSecondaryKey == keyCode)) {
					if(Common.debug()) Log.d(tag, "Registring first secondary key");
					
					mIsSecondaryDown = true;
					
					mSecondaryKey = keyCode;
					newEvent = true;
					
				} else {
					if(Common.debug()) Log.d(tag, "Registring first primary key");
					
					mIsPrimaryDown = true;
					mIsSecondaryDown = false;
					mIsAggregatedDown = true;
					mReset = false;
					mCancel = false;
					
					mPrimaryKey = keyCode;
					mSecondaryKey = 0;
					mSpecialKey = 0;
					mTaps = 1;
					this.firstDown = this.currDown = time;
					
					mIsCallButton = mPreferences.getBooleanGroup(Index.bool.key.remapCallButton, (mPrimaryKey + ":" + mSecondaryKey), Index.bool.value.remapCallButton);
					newEvent = true;
				}
				
				if (newEvent) {
					mLongPressIsSet = false;

					mFinished = false;
				}
				
			} else {
				if (keyCode == mPrimaryKey) {
					if(Common.debug()) Log.d(tag, "Releasing primary key");
					
					mIsPrimaryDown = false;
					
				} else if (keyCode == mSecondaryKey) {
					if(Common.debug()) Log.d(tag, "Releasing secondary key");
					
					mIsSecondaryDown = false;
				}
				if (!mIsPrimaryDown && (mSecondaryKey == 0 || !mIsSecondaryDown)) {
					//Down until both are up
					mIsAggregatedDown = false;
				}
			}
			return newEvent;
		}
		
		public Boolean wasInvoked() {
			return mFinished || mCancel;
		}
		
		public Boolean wasCanceled() {
			return mCancel;
		}
		
		public Boolean isDone() {
			return mFinished || mReset || mCancel || mPrimaryKey == 0;
		}
		
		public Boolean isMulti() {
			return mPrimaryKey > 0 && mSecondaryKey > 0;
		}
		
		public int getTaps() {
			return mTaps;
		}
		
		public Boolean isKeyDown() {
			return mIsAggregatedDown;
		}
		
		public Boolean isCallButton() {
			return mIsCallButton;
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
		
		public long firstDownTime() {
			return this.firstDown;
		}
		
		public long currDown() {
			return this.currDown;
		}
		
		public void setOngoingKeyCode(int primaryKeyCode, int secondaryKeyCode) {
			mOngoingKeyCodes[0] = primaryKeyCode;
			mOngoingKeyCodes[1] = secondaryKeyCode;
		}
		
		public int[] getOngoingKeyCodes() {
			return mOngoingKeyCodes;
		}
		
		public boolean isOngoingKeyCode() {
			return mOngoingKeyCodes[0] > 0;
		}
		public boolean isOngoingKeyCode(int keyCode) {
			return mOngoingKeyCodes[0] == keyCode || mOngoingKeyCodes[1] == keyCode;
		}
		
		public Boolean isOngoingLongPress() {
			return mLongPressIsSet;
		}
		
		public void setOngoingLongPress(Boolean on) {
			mLongPressIsSet = on;
		}
		
		public void setSpecialKey(int code) {
			mSpecialKey = code;
		}
		public Integer getSpecialKey() {
			return mSpecialKey;
		}
		
	}
}
