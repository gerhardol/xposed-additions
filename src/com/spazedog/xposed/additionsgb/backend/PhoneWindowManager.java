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
import android.os.Vibrator;
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
			
			final PhoneWindowManager hooks = new PhoneWindowManager();
			final ReflectClass pwm = ReflectClass.forName("com.android.internal.policy.impl.PhoneWindowManager");
			
			try {
				pwm.inject(hooks.hook_constructor);
				pwm.inject("init", hooks.hook_init);
				pwm.inject("interceptKeyBeforeQueueing", hooks.hook_interceptKeyBeforeQueueing);
				pwm.inject("interceptKeyBeforeDispatching", hooks.hook_interceptKeyBeforeDispatching);
				pwm.inject("performHapticFeedbackLw", hooks.hook_performHapticFeedbackLw);
				
			} catch (final ReflectException ei) {
				Log.e(TAG, ei.getMessage(), ei);
				
				pwm.removeInjections();
			}

		} catch (final ReflectException e) {
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
	protected static int FLAG_WAKE;
	
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
	protected PendingEvents mPendingEvents = new PendingEvents();
	private int m_resetAtPowerPress = -1;
	
	protected Object mLockQueueing = new Object();
	
	protected boolean mWasScreenOn = true;
	
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
			
		} catch (final ReflectException e) {}
		
		try {
			/*
			 * This does not exists in all non-AOSP versions
			 */
			mMethods.put("showGlobalActionsDialog", mPhoneWindowManager.findMethodDeep("showGlobalActionsDialog")); 
		} catch (final ReflectException e) {}
		
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
			final ReflectClass context = new ReflectClass(mContext);
			
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
	
				final ReflectClass wmp = ReflectClass.forName("android.view.WindowManagerPolicy");
				
				FLAG_INJECTED = (Integer) wmp.findField("FLAG_INJECTED").getValue();
				FLAG_VIRTUAL = (Integer) wmp.findField("FLAG_VIRTUAL").getValue();
				FLAG_WAKE = (Integer) ((wmp.findField("FLAG_WAKE").getValue())) | (Integer) ((wmp.findField("FLAG_WAKE_DROPPED").getValue()));
				
				ACTION_PASS_QUEUEING = (Integer) wmp.findField("ACTION_PASS_TO_USER").getValue();
				ACTION_DISABLE_QUEUEING = 0;
				
				ACTION_PASS_DISPATCHING = SDK_NEW_PHONE_WINDOW_MANAGER ? 0 : false;
				ACTION_DISABLE_DISPATCHING = SDK_NEW_PHONE_WINDOW_MANAGER ? -1 : true;
						
				if (SDK_HAS_HARDWARE_INPUT_MANAGER) {
					INJECT_INPUT_EVENT_MODE_ASYNC = (Integer) ReflectClass.forName("android.hardware.input.InputManager").findField("INJECT_INPUT_EVENT_MODE_ASYNC").getValue();
				}
				
			} catch (final ReflectException e) {
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
				final ReflectClass torchConstants = ReflectClass.forName("com.android.internal.util.cm.TorchConstants");
				mTorchIntent = new Intent((String) torchConstants.findField("ACTION_TOGGLE_STATE").getValue());
				
			} catch (final ReflectException er) {
				/*
				 * Search for Torch Apps that supports <package name>.TOGGLE_FLASHLIGHT intents
				 */
				final PackageManager pm = mContext.getPackageManager();
				final List<PackageInfo> packages = pm.getInstalledPackages(0);
				
				for (final PackageInfo pkg : packages) {
					final Intent intent = new Intent(pkg.packageName + ".TOGGLE_FLASHLIGHT");
					final List<ResolveInfo> recievers = pm.queryBroadcastReceivers(intent, 0);
					
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
					public void onBroadcastReceive(final String action, final Bundle data) {
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
						public void onReceive(final Context context, final Intent intent) {
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
									public void onError(final ReflectMember<?> member) {
										member.getReflectClass().setReceiver(
												mPhoneWindowManager.findField( SDK_HAS_KEYGUARD_DELEGATE ? "mKeyguardDelegate" : "mKeyguardMediator" ).getValue()
										);
									}
								});
								
								mRecentApplicationsDialog = ReflectClass.forName( SDK_NEW_RECENT_APPS_DIALOG ? "com.android.internal.statusbar.IStatusBarService" : "com.android.internal.policy.impl.RecentApplicationsDialog" );
								mRecentApplicationsDialog.setOnReceiverListener(new OnReceiverListener(){
									@Override
									public Object onReceiver(final ReflectMember<?> member) {
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
									public void onError(final ReflectMember<?> member) {
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
									final ReflectClass wc = ReflectClass.forName("android.view.ViewConfiguration");

									wc.inject("getLongPressTimeout", hook_viewConfigTimeouts);
									wc.inject("getGlobalActionKeyTimeout", hook_viewConfigTimeouts);
									
								} catch (final ReflectException e) {
									Log.e(TAG, e.getMessage(), e);
								}
								
							} catch (final ReflectException e) {
								Log.e(TAG, e.getMessage(), e);
								
								mPhoneWindowManager.removeInjections();
							}
						}
					}, new IntentFilter("android.intent.action.BOOT_COMPLETED")
				);
				
			} catch (final ReflectException e) {
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
		
		protected boolean mIsOriginalLocked = false;
		
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
			
			final String tag = TAG + "#Queueing/" + (down ? "Down " : "Up ") + keyCode + ":" + shortTime() + "(" + mKeyFlags.getTaps() + "," + repeatCount+ "):" ;

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
					final List<String> forcedKeys = (ArrayList<String>) mPreferences.getStringArray(Index.array.key.forcedHapticKeys, Index.array.value.forcedHapticKeys);
					
					if (forcedKeys.contains("" + keyCode)) {
						isVirtual = true;
					}
				}
				
				if (down && isVirtual) {
					performHapticFeedback(HAPTIC_VIRTUAL_KEY);
				}
				
				synchronized(mLockQueueing) {
					if (mPendingEvents.isOngoingKeyCode()) {
						//Release ongoing (long press) key actions
						if(Common.debug()) Log.d(tag, "Releasing long press event for " + mPendingEvents.getOngoingKeyCodes()[0]);

						injectInputEvent(mPendingEvents.getOngoingKeyCodes()[0], mKeyFlags.firstDownTime(), -1, false, true);
						if(mPendingEvents.getOngoingKeyCodes()[1] > 0) {
							injectInputEvent(mPendingEvents.getOngoingKeyCodes()[1], mKeyFlags.firstDownTime(), -1, false, true);								
						}
						mPendingEvents.setOngoingKeyCode(0, 0, false);
					} 
				}
				
				//Get new event, clear ongoing/special
				final boolean	newAction = mKeyFlags.registerKey(keyCode, down, downTime);
				if (isScreenOn && mInterceptKeyCode) {
					if (down) {
						if(Common.debug()) Log.d(tag, "Intercepting key code");
						
						final Bundle bundle = new Bundle();
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

							pokeUserActivity(downTime, false);
						}
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
		
		protected boolean mIsOriginalLocked = false;
		
		private final void wakeKeyHandling(final int policyFlags) {
			final boolean isWakeKey = (policyFlags & FLAG_WAKE) != 0;
			//Non configured Wake keys must be explicitly handled, the module seem to affect normal handling
			//(Partial wake locks set for all configured events)
			//DROP_REASON_DISABLED: Dropped event because input dispatch is disabled
			if (isWakeKey && !mWasScreenOn) {
				changeDisplayState(mKeyFlags.firstDownTime(), true);
			}
		}
		
		//Most ROM reboots after holding Power for 8-12s
		//For those missing (like Omate TrueSmart) this is kind of a replacement
		//Note that Power does not repeat, only sent once so this must be launched first time only
		private final void checkPowerPress(final int keyCode, final KeyFlags flags, final String tag)
		{
			if(m_resetAtPowerPress < 0) {
				m_resetAtPowerPress = mPreferences.getInt("power_press_delay_reset", 15);
			}
			if ((m_resetAtPowerPress > 0) && (keyCode == KeyEvent.KEYCODE_POWER) &&
					(flags.mPrimaryKey == keyCode) && (flags.mSecondaryKey == 0) &&
					flags.mIsAggregatedDown == true && flags.getTaps() == 1) {
				
				final KeyFlags wasFlags = flags.CloneFlags();
				final int delay = m_resetAtPowerPress * 1000;
				
				mHandler.postDelayed(new Runnable() {
					public void run() {
						if (mKeyFlags.SameFlags(wasFlags))
						{
							if(Common.debug()) Log.d(tag, "Power press for " + m_resetAtPowerPress + "s, rebooting");
							((PowerManager) mPowerManager.getReceiver()).reboot(null);
						}
					}
				}, delay);
			}
		}
		
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
			final String tag = TAG + "#Dispatch/" + (down ? "Down " : "Up ") + keyCode + ":" + shortTime() + "(" + mKeyFlags.getTaps() + "," + repeatCount+ "):" ;
			
			checkPowerPress(keyCode, mKeyFlags, tag);

			//Skipped unconfigured key, except Power that need handling
			if (!mKeyConfig.hasAnyAction() && (!mWasScreenOn || (keyCode != KeyEvent.KEYCODE_POWER))) {
				if(Common.debug()) Log.d(tag, "No mapped action");
				if (down) {
					wakeKeyHandling(policyFlags);
				}
				return;
			}

			/*
			 * Using KitKat work-around from the InputManager Hook
			 */
			final boolean isInjected = SDK_HAS_HARDWARE_INPUT_MANAGER ? 
					(((KeyEvent) param.args[1]).getFlags() & FLAG_INJECTED) != 0 : (policyFlags & FLAG_INJECTED) != 0;

			if (isInjected) {
				if (down && mKeyFlags.isKeyDown() && mPendingEvents.isOngoingKeyCode(keyCode)) { 

					//Limit rate for key repeat
					//It seems natural to pass first down event immediately but then first is longpress
					//(normally second event should be longpress)
					if(Common.debug()) Log.d(tag, "Delay long press key repeat "+((long) SystemClock.uptimeMillis() - mKeyFlags.currDown())+" ");

					int curDelay = 0;
					{ //wait block
						final KeyFlags wasFlags = mKeyFlags.CloneFlags();
						final int longLongPressDelay = 2; //Wait 2 times normal long press
						curDelay = (!mPendingEvents.isOngoingLongPress()) ? 
								//start key repeat and long press timeout are the same by default, not using ViewConfiguration.getKeyRepeatTimeout()
								longLongPressDelay * mKeyConfig.getLongPressDelay() :
									(SDK_NEW_VIEWCONFIGURATION ? ViewConfiguration.getKeyRepeatDelay() : 50);

						do {
							final int t = 10;
							try {
								Thread.sleep(t);

							} catch (final Throwable e) {}

							curDelay -= t;
						} while (mKeyFlags.SameFlags(wasFlags) && curDelay > 0);
					}
					
					synchronized(mLockQueueing) {
						if (curDelay <= 0) {
							if (Common.debug()) Log.d(tag, "Long press key repeat "+((long) SystemClock.uptimeMillis() - mKeyFlags.currDown()));
							
							if (!mPendingEvents.isOngoingLongPress()) {
								//The second down should be long press
								if(Common.debug()) Log.d(tag, "Setting long press on the mapped key:" + keyCode);

								performHapticFeedback(HAPTIC_LONG_PRESS);
								mPendingEvents.setOngoingKeyCode(keyCode, 0, true);
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
				

			} else if (!mKeyFlags.wasInvoked()) {
				if (down) {
					if (Common.debug()) Log.d(tag, "Waiting for long press timeout");
					
					int curDelay = mKeyConfig.getLongPressDelay();
					final KeyFlags wasFlags = mKeyFlags.CloneFlags();

						do {
							final int t = 10;
							try {
								Thread.sleep(t);

							} catch (final Throwable e) {}

							curDelay -= t;

						} while (mKeyFlags.SameFlags(wasFlags) && curDelay > 0);
					
					int specialKey = 0;
					synchronized(mLockQueueing) {
						if (!mKeyFlags.isDone() && curDelay <= 0) {
							performHapticFeedback(HAPTIC_LONG_PRESS);
							
							mKeyFlags.finish();
							final String keyAction = mKeyConfig.getAction(ActionTypes.press, mKeyFlags);

							if (mKeyConfig.isAction(keyAction)) {
								final int code = mKeyConfig.getEventKeyCode(keyAction, keyCode);
								//Feedback to the user that key long-press occurs, 
								//to give control of long-long press also if "Vibrate on Touch"
								//is not active (could be module specific, based on pattern)
								if (code > 0) {
									final int val = Settings.System.getInt(mContext.getContentResolver(),
											Settings.System.HAPTIC_FEEDBACK_ENABLED, 0);
									if (val == 0) {
										final Vibrator v = (Vibrator)mContext.getSystemService(Context.VIBRATOR_SERVICE);
										final long[] pattern = {0, 100};
										v.vibrate(pattern, -1);
									}
								}
								
								if (code == KeyEvent.KEYCODE_POWER) {
									if (Common.debug()) Log.d(tag,  shortTime() + " Invoking special key: " + code);
									//special handling for Power, sending first event when releasing
									specialKey = code;

								} else {
									if (Common.debug()) Log.d(tag,  shortTime() + " Invoking mapped long press action: " + keyAction);
									mPendingEvents.setOngoingKeyCode(code, 0, false);
									handleKeyAction(keyAction, code, mKeyFlags.firstDownTime(), false);
								}

							} else if (mKeyFlags.getTaps() > 1) {
								//Check not needed while the "early" tap detection skips press
								if(Common.debug()) Log.d(tag, "No default long press action for press: " + mKeyFlags.getTaps());
								
							} else {
								if(Common.debug()) Log.d(tag, "Invoking default long press action: " + keyCode);
																
								mPendingEvents.setOngoingKeyCode(mKeyFlags.getPrimaryKey(), mKeyFlags.getSecondaryKey(), true);
								
								wakeKeyHandling(policyFlags);
								//This is necessary, even if event is passed
								injectInputEvent(mKeyFlags.getPrimaryKey(), mKeyFlags.firstDownTime(), 0, false, policyFlags |KeyEvent.FLAG_LONG_PRESS);
								if(mKeyFlags.getSecondaryKey() > 0) {
									injectInputEvent(mKeyFlags.getSecondaryKey(), mKeyFlags.firstDownTime(), 0, false, policyFlags |KeyEvent.FLAG_LONG_PRESS);
								}
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
					
					if (specialKey > 0) {
						//Keys where click/long-press is decided instantly
						curDelay = 2 * mKeyConfig.getLongPressDelay();

						do {
							final int t = 10;
							try {
								Thread.sleep(t);
							} catch (final Throwable e) {}

							curDelay -= t;

						} while (mKeyFlags.SameFlags(wasFlags) && curDelay > 0);

						synchronized(mLockQueueing) {
							if (curDelay <= 0) {
								if(Common.debug()) Log.d(tag, shortTime() + " Invoking long press for special key long press action: " + specialKey);

								performHapticFeedback(HAPTIC_LONG_PRESS);
								mPendingEvents.setOngoingKeyCode(specialKey, 0, true);
								injectInputEvent(specialKey, mKeyFlags.firstDownTime(), 0, true, false);

							} else {
								if(Common.debug()) Log.d(tag, shortTime() + " Short press for special key long press event " + specialKey);

								injectInputEvent(specialKey, mKeyFlags.firstDownTime(), 0, false, true);
							}
						}
					}
					
				} else {
					//Key up 
					//timeout if there are events following, otherwise direct action (or default first)
					int curDelay = 0;
					if (mKeyConfig.hasMoreAction(ActionTypes.tap, mKeyFlags, true)) {
						if(Common.debug()) Log.d(tag, "Waiting for tap timeout");
						
						curDelay = mKeyConfig.getDoubleTapDelay();
						final KeyFlags wasFlags = mKeyFlags.CloneFlags();
						do {
							final int t = 10;
							try {
								Thread.sleep(t);
								
							} catch (final Throwable e) {}
							
							curDelay -= t;
							
						} while (mKeyFlags.SameFlags(wasFlags) && curDelay > 0);
					}
					
					synchronized(mLockQueueing) {
						if (!mKeyFlags.isDone() && (curDelay <= 0) && mKeyFlags.getCurrentKey() == keyCode) {

							mKeyFlags.finish();
							int callCode = 0;
							
							if ((mKeyFlags.getTaps() == 1) && mKeyConfig.isCallButton()) {
								final int mode = ((AudioManager) mAudioManager.getReceiver()).getMode();

								if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
									callCode = KeyEvent.KEYCODE_ENDCALL;

								} else if (mode == AudioManager.MODE_RINGTONE) {
									callCode = KeyEvent.KEYCODE_CALL;
								}
							}
							
							if (callCode == 0) {
								final String keyAction = mKeyConfig.getAction(ActionTypes.tap, mKeyFlags);
								if (mKeyConfig.hasAction(ActionTypes.tap, mKeyFlags)) {
									if (Common.debug()) Log.d(tag, shortTime() + " Invoking click action: " + keyAction);
								
									final int code = mKeyConfig.getEventKeyCode(keyAction, keyCode);
									handleKeyAction(keyAction, code, mKeyFlags.firstDownTime(), true);
									
								} else if (mKeyFlags.getTaps() > 1) {
									if (Common.debug()) Log.d(tag, shortTime() + " No mapped click action" + mKeyFlags.getTaps());
									
								} else {
									if (Common.debug()) Log.d(tag, shortTime() + " Invoking default click key:" + 
									mKeyFlags.getPrimaryKey() + "," + mKeyFlags.getSecondaryKey());
									
									wakeKeyHandling(policyFlags);
									//We cannot dispatch this event, must supply down first
									injectInputEvent(mKeyFlags.getPrimaryKey(), mKeyFlags.firstDownTime(), 0, true, policyFlags);
									if(mKeyFlags.getSecondaryKey() > 0) {
										injectInputEvent(mKeyFlags.getSecondaryKey(), mKeyFlags.firstDownTime(), 0, true, policyFlags);
									}
								}
								
							} else {
								if (Common.debug()) Log.d(tag, shortTime() + " Invoking call button");
								
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
	
	protected Boolean internalKey(final Integer keyCode) {
		if (!mDeviceKeys.containsKey(keyCode)) {
			mDeviceKeys.put(keyCode, KeyCharacterMap.deviceHasKey(keyCode));
		}
		
		return mDeviceKeys.get(keyCode) || mPreferences.getBoolean(Common.Index.bool.key.remapAllowExternals, Common.Index.bool.value.remapAllowExternals);
	}
	
	@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
	public void pokeUserActivity(final long time, final Boolean forced) {
		if (forced) {
			if (SDK_NEW_POWER_MANAGER) {
				((PowerManager) mPowerManager.getReceiver()).wakeUp(time);
				
			} else {
				/*
				 * API's below 17 does not support PowerManager#wakeUp, so
				 * instead we will trick our way into the hidden IPowerManager#forceUserActivityLocked which 
				 * is not accessible trough the regular PowerManager class. It the same method that 
				 * turns on the screen when you plug in your USB cable.
				 */
				try {
					mMethods.get("forceUserActivityLocked").invoke();
					
				} catch (final ReflectException e) {
					Log.e(TAG, e.getMessage(), e);
				}
			}
			
		} else {
			((PowerManager) mPowerManager.getReceiver()).userActivity(time, true);
		}
	}

	@SuppressLint("NewApi")
	protected void changeDisplayState(final long time, final Boolean on) {
		if (on) {
			pokeUserActivity(time, true);
			
		} else {
			((PowerManager) mPowerManager.getReceiver()).goToSleep(time);
		}
	}
	
	protected void injectInputEvent(final int keyCode, final long time, final int repeatDown, final boolean longpress, final boolean up) {
		synchronized(PhoneWindowManager.class) {
			//Note: longpress and up should not be set simultaneously
			final int flags = longpress ? KeyEvent.FLAG_LONG_PRESS|KeyEvent.FLAG_FROM_SYSTEM|FLAG_INJECTED : KeyEvent.FLAG_FROM_SYSTEM|FLAG_INJECTED;
			injectInputEvent(keyCode, time, repeatDown, up, flags);
		}
    }
	
	@SuppressLint("NewApi")
	protected void injectInputEvent(final int keyCode, final long time, final int repeatDown, final boolean up, final int flags) {
		/*
		 * We handle display on here, because some devices has issues
		 * when executing handlers while in deep sleep. 
		 * Some times they will need a few key presses before reacting. 
		 */
		if (keyCode == KeyEvent.KEYCODE_POWER && !mWasScreenOn) {
			changeDisplayState(time, true);
			return;
		}
		
		synchronized(PhoneWindowManager.class) {
			final long now = SystemClock.uptimeMillis();
			final int characterMap = SDK_NEW_CHARACTERMAP ? KeyCharacterMap.VIRTUAL_KEYBOARD : 0;
			
			try {
				if(repeatDown >= 0) {
					final KeyEvent event = new KeyEvent(time, now, KeyEvent.ACTION_DOWN, keyCode, repeatDown, 0, characterMap, 0, flags, InputDevice.SOURCE_KEYBOARD);
					if (SDK_HAS_HARDWARE_INPUT_MANAGER) {
						mMethods.get("injectInputEvent").invoke(event, INJECT_INPUT_EVENT_MODE_ASYNC);

					} else {
						mMethods.get("injectInputEvent").invoke(event);
					}
				}
				
				if (up) {
					final KeyEvent event = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0, characterMap, 0, flags, InputDevice.SOURCE_KEYBOARD);
					if (SDK_HAS_HARDWARE_INPUT_MANAGER) {
						mMethods.get("injectInputEvent").invoke(KeyEvent.changeAction(event, KeyEvent.ACTION_UP), INJECT_INPUT_EVENT_MODE_ASYNC);
						
					} else {
						mMethods.get("injectInputEvent").invoke(KeyEvent.changeAction(event, KeyEvent.ACTION_UP));
					}
				}
				
			} catch (final ReflectException e) {
				Log.e(TAG, e.getMessage(), e);
			}
		}
	}

	protected void performHapticFeedback(final Integer effectId) {
		try {
			mMethods.get("performHapticFeedback").invoke(null, effectId, false);
			
		} catch (final ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}
	
	protected Boolean isKeyguardShowing() {
		try {
			return (Boolean) mMethods.get("KeyguardMediator.isShowing").invoke();
			
		} catch (final ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
		}
		
		return false;
	}
	
	protected Boolean isKeyguardLockedAndInsecure() {
		if (isKeyguardLocked()) {
			try {
				return !((Boolean) mMethods.get("KeyguardMediator.isRestricted").invoke());
				
			} catch (final ReflectException e) {
				Log.e(TAG, e.getMessage(), e);
			}
		}
		
		return false;
	}
	
	protected Boolean isKeyguardLocked() {
		try {
			return (Boolean) mMethods.get("KeyguardMediator.isLocked").invoke();
			
		} catch (final ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
		}
		
		return false;
	}
	
	protected void keyGuardDismiss() {
		if (isKeyguardLocked()) {
			try {
				mMethods.get("KeyguardMediator.dismiss").invoke(false, true);
				
			} catch (final ReflectException e) {
				Log.e(TAG, e.getMessage(), e);
			}
		}
	}
	
	protected String getRunningPackage() {
		final List<ActivityManager.RunningTaskInfo> packages = ((ActivityManager) mActivityManager.getReceiver()).getRunningTasks(1);
		
		return packages.size() > 0 ? packages.get(0).baseActivity.getPackageName() : null;
	}
	
	protected String getHomePackage() {
		final Intent intent = new Intent(Intent.ACTION_MAIN);
		intent.addCategory(Intent.CATEGORY_HOME);
		final ResolveInfo res = mContext.getPackageManager().resolveActivity(intent, 0);
		
		return res.activityInfo != null && !"android".equals(res.activityInfo.packageName) ? 
				res.activityInfo.packageName : "com.android.launcher";
	}
	
	protected void launchApplication(final String packageName) {
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
		startIntent(intent);
	}
	
	protected void startIntent(Intent intent) {
		if (SDK_HAS_MULTI_USER) {
			try {
				final Object userCurrent = mFields.get("UserHandle.current").getValue();
				final Object user = mConstructors.get("UserHandle").invoke(userCurrent);
				
				mMethods.get("startActivityAsUser").invoke(intent, user);
				
			} catch (final ReflectException e) {
				Log.e(TAG, e.getMessage(), e);
			}
			
		} else {
			mContext.startActivity(intent);
		}
	}
	
	//Requires API level 12, checked for available actions
	@SuppressLint("NewApi") 
	protected void toggleLastApplication() {
		final List<RecentTaskInfo> packages = ((ActivityManager) mActivityManager.getReceiver()).getRecentTasks(5, ActivityManager.RECENT_IGNORE_UNAVAILABLE);
		ActivityManager.RecentTaskInfo lastAppInfo = null;
		final String homePackage = getHomePackage();
			
		for (int i=1; i < packages.size(); i++) {
			final ActivityManager.RecentTaskInfo recentInfo = packages.get(i);
			final String intentString = recentInfo.baseIntent + "";
			
			final int indexStart = intentString.indexOf("cmp=")+4;
			final int indexStop = intentString.indexOf("/", indexStart);
			
			final String packageName = intentString.substring(indexStart, indexStop);

			if (!packageName.equals(homePackage) && !packageName.equals("com.android.systemui")) {
				lastAppInfo = recentInfo;
				break;
			}
		}
		if (lastAppInfo != null) {
			if (lastAppInfo.id > 0) {
				((ActivityManager) mActivityManager.getReceiver()).moveTaskToFront(lastAppInfo.id, ActivityManager.MOVE_TASK_NO_USER_ACTION);
			} else {
				lastAppInfo.baseIntent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT); 
				startIntent(lastAppInfo.baseIntent);
			}
		} else {
			Toast.makeText(mContext, "No previous app", Toast.LENGTH_SHORT).show();
		}
	}
	
	protected void sendCloseSystemWindows(final String reason) {
		if(Common.debug()) Log.d(TAG, "Closing all system windows");
		
		try {
			mMethods.get("closeSystemDialogs").invoke(reason);
			
		} catch (final ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}

	protected void openRecentAppsDialog() {
		if(Common.debug()) Log.d(TAG, "Invoking Recent Application Dialog");
		
		sendCloseSystemWindows("recentapps");
		
		try {
			mMethods.get("toggleRecentApps").invoke();
			
		} catch (final ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}
	
	protected void openGlobalActionsDialog() {
		if(Common.debug()) Log.d(TAG, "Invoking Global Actions Dialog");
		
		sendCloseSystemWindows("globalactions");
		
		try {
			mMethods.get("showGlobalActionsDialog").invoke();
			
		} catch (final ReflectException e) {
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
					
				} catch (final ReflectException e) {
					Log.e(TAG, e.getMessage(), e);
				}
				
			} else {
				try {
					mMethods.get("thawRotation").invoke();
					
				} catch (final ReflectException e) {
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

		} catch (final ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
		}
		
		return 0;
	}
	
	protected Integer getNextRotation(final Boolean backwards) {
		final Integer  position = getCurrentRotation();
		
		return (position == Surface.ROTATION_90 || position == Surface.ROTATION_0) && backwards ? 270 : 
			(position == Surface.ROTATION_270 || position == Surface.ROTATION_0) && !backwards ? 90 : 0;
	}
	
	protected void killForegroundApplication() {
		if(Common.debug()) Log.d(TAG, "Start searching for foreground application to kill");
		
		final List<ActivityManager.RunningTaskInfo> packages = ((ActivityManager) mActivityManager.getReceiver()).getRunningTasks(5);
		
		for (int i=0; i < packages.size(); i++) {
			final String packageName = packages.get(0).baseActivity.getPackageName();
			
			if (!packageName.equals(getHomePackage()) && !packageName.equals("com.android.systemui")) {
				if(Common.debug()) Log.d(TAG, "Invoking force stop on " + packageName);
				
				try {
					if (SDK_HAS_MULTI_USER) {
						mMethods.get("forceStopPackage").invoke(packageName, mFields.get("UserHandle.current").getValue());
	
					} else {
						mMethods.get("forceStopPackage").invoke(packageName);
					}
					
				} catch (final ReflectException e) {
					Log.e(TAG, e.getMessage(), e);
				}
			}
		}
	}
	
	protected void takeScreenshot() {
		try {
			mMethods.get("takeScreenshot").invoke();
			
		} catch (final ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}
	
	protected void toggleFlashLight() {
		if (mTorchIntent != null) {
			if (SDK_HAS_MULTI_USER) {
				try {
					final Object userCurrent = mFields.get("UserHandle.current").getValue();
					final Object user = mConstructors.get("UserHandle").invoke(userCurrent);
					
					mMethods.get("sendBroadcastAsUser").invoke(mTorchIntent, user);
					
				} catch (final ReflectException e) {
					Log.e(TAG, e.getMessage(), e);
				}
				
			} else {
				mContext.sendBroadcast(mTorchIntent);
			}
		}
	}
	
	protected void handleKeyAction(final String action, final int code, final long time, final boolean upEvent) {
		/*
		 * This should always be wrapped and sent to a handler. 
		 * If this is executed directly, some of the actions will crash with the error 
		 * -> 'Can't create handler inside thread that has not called Looper.prepare()'
		 */
		mHandler.post(new Runnable() {
			public void run() {
				final String actionType = Common.actionType(action);
				
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
		private final String[] mActions; // new String[maxTapActions];
		private int mKeyDelayTap = 0;
		private int mKeyDelayPress = 0;
		private boolean mAnyAction = false;
		private boolean mIsCallButton = false;
		
		public KeyConfig() {
			mActions = new String[]{null, null, null, null, null, null};
		}
		
		public void newAction(final KeyFlags keyFlags, final boolean isScreenOn)
		{
			mIsCallButton = mPreferences.getBooleanGroup(Index.bool.key.remapCallButton, (keyFlags.getPrimaryKey() + ":" + keyFlags.getSecondaryKey()), Index.bool.value.remapCallButton);
			
			final boolean extended = mPreferences.isPackageUnlocked();
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
				final String keyGroupName = mKeyFlags.getPrimaryKey() + ":" + mKeyFlags.getSecondaryKey();
				final String appCondition = !isScreenOn ? null : 
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
		
		public boolean hasAnyAction() {
			return mAnyAction;
		}
		
		private int getIndex(final ActionTypes atype, final KeyFlags keyFlags) {
			int index = (keyFlags.getTaps() - 1) * 2;
			if (atype == ActionTypes.tap) {index++;}
			return index;
		}
		
		public String getAction(final ActionTypes atype, final KeyFlags keyFlags) {
			final int index = getIndex(atype, keyFlags);
			if(index > mActions.length) {return null;}
			return mActions[index];
		}
		
		public boolean isAction(final String action) {
			return (action != null);
		}
		
		public boolean hasAction(final ActionTypes atype, final KeyFlags keyFlags) {
			return isAction(getAction(atype, keyFlags));
		}
		
		public boolean hasMoreAction(final ActionTypes atype, final KeyFlags keyFlags, final boolean next) {
			boolean result = false;
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
		
		public int getDoubleTapDelay() {
			return mKeyDelayTap;
		}
		
		public int getLongPressDelay() {
			return mKeyDelayPress;
		}
		
		//static get the possible keycode for the event (or default)
		public int getEventKeyCode(final String action, final int keyCode) {
			return (action != null && action.matches("^[0-9]+$") ? Integer.parseInt(action) : 
				(action == null ? keyCode : 0));
		}
		
		public boolean isCallButton() {
			return mIsCallButton;
		}
	}
	
	protected class PendingEvents {
		private final int[] mOngoingKeyCodes; // new int[2];
		private boolean mLongPressIsSet = false;
	
		public PendingEvents() {
			mOngoingKeyCodes = new int[]{0, 0};
		}
		
		// key status about ongoing events
		public void setOngoingKeyCode(final int primaryKeyCode, final int secondaryKeyCode, final boolean isLong) {
			mOngoingKeyCodes[0] = primaryKeyCode;
			mOngoingKeyCodes[1] = secondaryKeyCode;
			mLongPressIsSet = isLong;
		}
		
		public int[] getOngoingKeyCodes() {
			return mOngoingKeyCodes;
		}
		
		public boolean isOngoingKeyCode() {
			return (mOngoingKeyCodes[0] > 0);
		}
		public boolean isOngoingKeyCode(final int keyCode) {
			return (mOngoingKeyCodes[0] == keyCode) || (mOngoingKeyCodes[1] == keyCode);
		}
		
		public boolean isOngoingLongPress() {
			return mLongPressIsSet;
		}
	}
	
	//Status of keys and combined events
	protected class KeyFlags {
		private boolean mIsPrimaryDown = false;
		private boolean mIsSecondaryDown = false;
		private boolean mIsAggregatedDown = false;
		private boolean mFinished = false;
		private boolean mReset = false;
		private boolean mCancel = false;
		
		private int mTaps = 0; //Corresponds to eventCount in KeyEvent() (often mixed with repeatCount)
		private int mPrimaryKey = 0;
		private int mSecondaryKey = 0;
		private int mCurrentKey = 0;
		
		private long mFirstDown; //time
		private long mCurrDown;
		
		public KeyFlags CloneFlags() {
			final KeyFlags k = new KeyFlags();
			k.mIsPrimaryDown = this.mIsPrimaryDown;
			k.mIsSecondaryDown = this.mIsSecondaryDown;
			k.mTaps = this.mTaps;
			k.mPrimaryKey = this.mPrimaryKey;
			k.mSecondaryKey = this.mSecondaryKey;
			k.mFirstDown = this.mFirstDown;
			return k;
		}
		
		public boolean SameFlags(final KeyFlags o2) {
			boolean result = false;
			if (this.mIsPrimaryDown == o2.mIsPrimaryDown &&
					this.mIsSecondaryDown == o2.mIsSecondaryDown &&
					this.mTaps == o2.mTaps &&
					this.mPrimaryKey == o2.mPrimaryKey &&
					this.mSecondaryKey == o2.mSecondaryKey &&
					this.mFirstDown == o2.mFirstDown) {
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
		
		public boolean registerKey(final int keyCode, final boolean down, final long time) {
			boolean newEvent = false;
			mCurrentKey = keyCode;

			final String tag = TAG + "#KeyFlags:" + keyCode;
			
			if ((time - this.mFirstDown) > 3000) {
				//Waited too long to handle this, for instance some screen off combination that is not well handled
				mReset = true;
			}

			if (down) {
				if (!isDone() && mTaps >= 1 && (keyCode == mPrimaryKey || keyCode == mSecondaryKey)) {
					if(Common.debug()) Log.d(tag, "Registering repeated event");
										
					if (!mIsPrimaryDown && !mIsSecondaryDown) {
						mTaps++;
						this.mCurrDown = SystemClock.uptimeMillis();
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
					if(Common.debug()) Log.d(tag, "Registering first secondary key");
					
					mIsSecondaryDown = true;
					
					mSecondaryKey = keyCode;
					newEvent = true;
					
				} else {
					if(Common.debug()) Log.d(tag, "Registering first primary key");
					
					mIsPrimaryDown = true;
					mIsSecondaryDown = false;
					mIsAggregatedDown = true;
					mReset = false;
					mCancel = false;
					
					mPrimaryKey = keyCode;
					mSecondaryKey = 0;
					mTaps = 1;
					this.mFirstDown = this.mCurrDown = time;
					
					newEvent = true;
				}
				
				if (newEvent) {
					mFinished = false;
				}
				
			} else {
				if (keyCode == mPrimaryKey) {
					if(Common.debug()) Log.d(tag, "Releasing primary key");
					mIsPrimaryDown = false;
					
				} else if (keyCode == mSecondaryKey) {
					if(Common.debug()) Log.d(tag, "Releasing secondary key");
					
					mIsSecondaryDown = false;
				} else {
					if(Common.debug()) Log.d(tag, "Releasing unknown key " + keyCode);
				}
				
				if (!mIsPrimaryDown && (mSecondaryKey == 0 || !mIsSecondaryDown)) {
					//Down until both are up
					mIsAggregatedDown = false;
				}
			}
			return newEvent;
		}
		
		public boolean wasInvoked() {
			return mFinished || mCancel;
		}
		
		public boolean wasCanceled() {
			return mCancel;
		}
		
		public boolean isDone() {
			return mFinished || mReset || mCancel || mPrimaryKey == 0;
		}
		
		public boolean isMulti() {
			return mPrimaryKey > 0 && mSecondaryKey > 0;
		}
		
		public int getTaps() {
			return mTaps;
		}
		
		public boolean isKeyDown() {
			return mIsAggregatedDown;
		}
		
		public int getPrimaryKey() {
			return mPrimaryKey;
		}
		
		public int getSecondaryKey() {
			return mSecondaryKey;
		}
		
		public int getCurrentKey() {
			return mCurrentKey;
		}
		
		public long firstDownTime() {
			return this.mFirstDown;
		}
		
		public long currDown() {
			return this.mCurrDown;
		}
		public String toString() {
			return ""+mIsPrimaryDown+mIsSecondaryDown+mIsAggregatedDown+mFinished+mReset+mCancel+mTaps+mPrimaryKey+mSecondaryKey+mCurrentKey;
		}
	}
}
