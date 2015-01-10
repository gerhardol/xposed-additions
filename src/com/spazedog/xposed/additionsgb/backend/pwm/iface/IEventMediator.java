package com.spazedog.xposed.additionsgb.backend.pwm.iface;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.dinglisch.android.tasker.TaskerIntent;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Vibrator;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.Surface;
import android.widget.Toast;

import com.spazedog.lib.reflecttools.ReflectClass;
import com.spazedog.lib.reflecttools.utils.ReflectException;
import com.spazedog.lib.reflecttools.utils.ReflectConstants.Match;
import com.spazedog.xposed.additionsgb.Common;
import com.spazedog.xposed.additionsgb.backend.pwm.EventManager;
import com.spazedog.xposed.additionsgb.backend.pwm.PhoneWindowManager;
import com.spazedog.xposed.additionsgb.backend.pwm.iface.IMediatorSetup.SDK;
import com.spazedog.xposed.additionsgb.backend.service.XServiceManager;
import com.spazedog.xposed.additionsgb.configs.Settings;

public abstract class IEventMediator extends IMediatorSetup {
	
	public static enum ActionType { CLICK, PRESS }
	public static enum StackAction { EXLUDE_HOME, INCLUDE_HOME, JUMP_HOME }
	
	private Map<Integer, Boolean> mDeviceIds = new HashMap<Integer, Boolean>();
	private ArrayList<String> mDeviceTypes;
	
	private Runnable mPowerHardResetVibrateRunnable = new Runnable(){
		@Override
		public void run() {
			long[] pattern = {50l, 100l, 50l, 50l};

			Vibrator vibrator = (Vibrator) (((Context) mContext.getReceiver()).getSystemService(Context.VIBRATOR_SERVICE));
			vibrator.vibrate(pattern, -1);
		}
	};

	private Runnable mPowerHardResetRunnable = new Runnable(){
		@Override
		public void run() {
			((PowerManager) mPowerManager.getReceiver()).reboot(null);
		}
	};

	protected IEventMediator(ReflectClass pwm, XServiceManager xServiceManager) {
		super(pwm, xServiceManager);
	}
	
	public Boolean validateDeviceType(Object event) {
		/*
		 * Gingerbread has no access to the KeyEvent in the intercept method.
		 * Instead we parse the keycode on these versions and skip the first check here. 
		 */
		KeyEvent keyEvent = event instanceof KeyEvent ? (KeyEvent) event : null;
		Integer keyCode = event instanceof KeyEvent ? keyEvent.getKeyCode() : (Integer) event;
		
		/*
		 * Older Android version does not parse the KeyEvent object to the PhoneWindowManager class.
		 * For these we validate individual key codes instead. Not as exact, but is does the job in most cases. 
		 */
		Integer deviceId = keyEvent != null ? keyEvent.getDeviceId() : keyCode;
		Integer allowExternals = -2;
		
		/*
		 * If the settings change, we have to re-validate the keys
		 */
		if (SDK.METHOD_INTERCEPT_VERSION > 1) {
			if (mDeviceTypes == null || !mDeviceTypes.equals(mXServiceManager.getStringArray(Settings.REMAP_EXTERNALS_LIST, mDeviceTypes))) {
				mDeviceIds.clear();
				mDeviceTypes = (ArrayList<String>) mXServiceManager.getStringArray(Settings.REMAP_EXTERNALS_LIST, new ArrayList<String>());
			}
			
		} else {
			if (!mDeviceIds.containsKey(allowExternals) || !mDeviceIds.get(allowExternals).equals(mXServiceManager.getBoolean(Settings.REMAP_ALLOW_EXTERNALS))) {
				mDeviceIds.clear();
				mDeviceIds.put(allowExternals, mXServiceManager.getBoolean(Settings.REMAP_ALLOW_EXTERNALS));
			}
		}
		
		if (!mDeviceIds.containsKey(deviceId)) {
			Boolean validated = true;
			
			if (keyEvent != null && keyEvent.getDeviceId() != -1) {
				Integer source = keyEvent.getSource();
				InputDevice device = keyEvent.getDevice();
				
				/*
				 * We do not want to handle regular Keyboards or gaming devices. 
				 * Do not trust KeyCharacterMap.getKeyboardType() as it can easily display anything
				 * as a FULL PC Keyboard. InputDevice.getKeyboardType() should be safer. 
				 */
				if (device != null && (device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC && !mDeviceTypes.contains("keyboard")) ||
						(((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
						|| (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
						|| (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) 
							&& !mDeviceTypes.contains("controller"))) {
					
					validated = false;
				}
			}
			
			/*
			 * Now that we know that the device type is supported, let's see if we should handle external once.
			 */
			if (validated && 
					((SDK.METHOD_INTERCEPT_VERSION == 1 && !mDeviceIds.get(allowExternals)) 
							|| (SDK.METHOD_INTERCEPT_VERSION > 1 && mDeviceTypes.isEmpty()))) {
				
				if (SDK.INPUT_DEVICESTORAGE_VERSION > 1) {

					try {
                        InputDevice device = keyEvent.getDevice();
						/*
						 * @Google get a grip, this method should be publicly accessible. Makes no sense to hide it.
						 */
						validated = device == null || (Boolean) mMethods.get("isDeviceExternal").invokeReceiver(device);

                    } catch (NullPointerException e) {
                        Log.e(TAG, e.getMessage(), e);
                    } catch (ReflectException e) {
                        Log.e(TAG, e.getMessage(), e);
                    }

				} else {
					validated = KeyCharacterMap.deviceHasKey(keyCode);
				}
			}
			
			mDeviceIds.put(deviceId, validated);
		}
		
		return mDeviceIds.get(deviceId);
	}
	
    public void injectInputEvent(KeyEvent keyEvent, Integer action, Integer repeatCount, Integer flags) {
        //ACTION_MULTIPLE is really abuse of the action, but calling code is shorter...
        Integer[] actions = action == KeyEvent.ACTION_MULTIPLE ? new Integer[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP} : new Integer[]{action};
        Long time = SystemClock.uptimeMillis();

        if ((flags & KeyEvent.FLAG_FROM_SYSTEM) == 0)
            flags |= KeyEvent.FLAG_FROM_SYSTEM;

        if ((flags & ORIGINAL.FLAG_INJECTED) == 0)
            flags |= ORIGINAL.FLAG_INJECTED;

        if ((flags & KeyEvent.FLAG_LONG_PRESS) == 0 && repeatCount == 1)
            flags |= KeyEvent.FLAG_LONG_PRESS;

        if ((flags & KeyEvent.FLAG_LONG_PRESS) != 0 && repeatCount != 1)
            flags &= ~KeyEvent.FLAG_LONG_PRESS;

        keyEvent = KeyEvent.changeTimeRepeat(keyEvent, time, repeatCount, flags);

        for (Integer keyAction: actions) {
            if (keyEvent.getAction() != keyAction) {
                keyEvent = KeyEvent.changeAction(keyEvent, keyAction);
            }

            injectInputEvent(keyEvent);
        }
    }

    @SuppressLint("NewApi")
    public void injectInputEvent(KeyEvent keyEvent) {
        synchronized (PhoneWindowManager.class) {
            try {
                if (SDK.MANAGER_HARDWAREINPUT_VERSION > 1) {
                    mMethods.get("injectInputEvent").invoke(keyEvent, ORIGINAL.INPUT_MODE_ASYNC);

                } else {
                    mMethods.get("injectInputEvent").invoke(keyEvent);
                }

            } catch (ReflectException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }
    }

	public void performHapticFeedback(KeyEvent keyEvent, Integer type, Integer policyFlags) {
		try {
			if (type == HapticFeedbackConstants.VIRTUAL_KEY) {
				List<String> forcedKeys = mXServiceManager.getStringArray(Settings.REMAP_LIST_FORCED_HAPTIC, null);
				Integer keyCode = 0;
                if (keyEvent != null) {
                    keyCode = keyEvent.getKeyCode();
                }
				
				if (forcedKeys == null || !forcedKeys.contains(""+keyCode)) {
					if (SDK.SAMSUNG_FEEDBACK_VERSION == 1) {
						mMethods.get("samsung.performSystemKeyFeedback").invokeOriginal(keyEvent); return;
						
					} else if (SDK.SAMSUNG_FEEDBACK_VERSION == 2) {
						mMethods.get("samsung.performSystemKeyFeedback").invokeOriginal(keyEvent, false, true); return;
						
					} else if ((policyFlags & ORIGINAL.FLAG_VIRTUAL) == 0) {
						return;
					}
				}
			}
			
			mMethods.get("performHapticFeedback").invokeOriginal(null, type, false);
			
		} catch (ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}

	public void performLongPressFeedback() {
		//Feedback to the user that key long-press occurs, 
		//to give control of long-long press. May duplicate normal feedback.
		final boolean val = mXServiceManager.getBoolean(Settings.ENABLE_LONGPRESS_FEEDBACK, false);
		if (val) {
			final Vibrator v = (Vibrator)(((Context) mContext.getReceiver()).getSystemService(Context.VIBRATOR_SERVICE));
			final long[] pattern = {0, 100};
			v.vibrate(pattern, -1);
		}
	}

	@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
	public void pokeUserActivity(Long time, Boolean forced) {
		if (forced) {
			if (SDK.MANAGER_POWER_VERSION > 1) {
				mMethods.get("wakeUp").invoke(time);
				
			} else {
				/*
				 * API's below 17 does not support PowerManager#wakeUp, so
				 * instead we will trick our way into the hidden IPowerManager#forceUserActivityLocked which 
				 * is not accessible trough the regular PowerManager class. It is the same method that 
				 * turns on the screen when you plug in your USB cable.
				 */
				try {
					mMethods.get("forceUserActivityLocked").invoke();
					
				} catch (ReflectException e) {
					Log.e(TAG, e.getMessage(), e);
				}
			}
			
		} else {
			if (!mWakelock.isHeld()) {
				mWakelock.acquire(3000);
			}
			
			if (SDK.MANAGER_POWER_VERSION == 1) {
				mMethods.get("userActivity").invoke(time, true);
				
			} else {
				mMethods.get("userActivity").invoke(time, 0, 1 << 0);
			}
		}
	}
	
	@SuppressLint("NewApi")
	public void changeDisplayState(Long time, Boolean on) {
		if (on) {
			pokeUserActivity(time, true);
			
		} else {
			if (SDK.MANAGER_POWER_VERSION > 3) {
				mMethods.get("goToSleep").invoke(time, 4, 0);
				
			} else if (SDK.MANAGER_POWER_VERSION >= 2) {
				mMethods.get("goToSleep").invoke(time, 0);
				
			} else {
				mMethods.get("goToSleep").invoke(time);
			}
		}
	}
	
	public void powerHardResetTimer(Integer keyCode, Boolean isKeyDown, int pressTimeout) {
		Integer delay = mXServiceManager.getInt(Settings.REMAP_TIMEOUT_HARD_RESET, 15000);
		if (delay > 0) {
			if (keyCode.equals(KeyEvent.KEYCODE_POWER) && isKeyDown) {
                //Set the vibrate warning quite late, after possible feedback
				int vibrateDelay = delay * 3/4;
				if (vibrateDelay < delay && vibrateDelay > 3*pressTimeout) {
					mHandler.postDelayed(mPowerHardResetVibrateRunnable, vibrateDelay);
				}
				Log.d(TAG, "Rebooting in " + delay + "ms (if no other key events)");
				mHandler.postDelayed(mPowerHardResetRunnable, delay);

			} else {
				mHandler.removeCallbacks(mPowerHardResetVibrateRunnable);
				mHandler.removeCallbacks(mPowerHardResetRunnable);
			}
		}
	}

	public Boolean isKeyguardShowing() {
		try {
			return (Boolean) mMethods.get("KeyguardMediator.isShowing").invoke();
			
		} catch (ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
		}
		
		return false;
	}
	
	public Boolean isKeyguardLockedAndInsecure() {
		if (isKeyguardLocked()) {
			try {
				return !((Boolean) mMethods.get("KeyguardMediator.isRestricted").invoke());
				
			} catch (ReflectException e) {
				Log.e(TAG, e.getMessage(), e);
			}
		}
		
		return false;
	}
	
	public Boolean isKeyguardLocked() {
		try {
			return (Boolean) mMethods.get("KeyguardMediator.isLocked").invoke();
			
		} catch (ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
		}
		
		return false;
	}
	
	public void keyGuardDismiss() {
		if (isKeyguardLocked()) {
			try {
				mMethods.get("KeyguardMediator.dismiss").invoke(false, true);
				
			} catch (ReflectException e) {
				Log.e(TAG, e.getMessage(), e);
			}
		}
	}
	
	public ActivityManager.RunningTaskInfo getPackageFromStack(Integer stack, StackAction action) {
		List<ActivityManager.RunningTaskInfo> packages = ((ActivityManager) mActivityManager.getReceiver()).getRunningTasks(5);
		String currentHome = action != StackAction.INCLUDE_HOME ? getHomePackage() : null;
		
		for (int i=stack; i < packages.size(); i++) {
			RunningTaskInfo taskInfo = packages.get(i);
			String baseName = taskInfo.baseActivity.getPackageName();
			
			if (!baseName.equals("com.android.systemui") && taskInfo.id != 0) {
				if (action == StackAction.INCLUDE_HOME || !baseName.equals(currentHome)) {
					return taskInfo;
					
				} else if (action == StackAction.JUMP_HOME) {
					continue;
				}
				
				break;
			}
		}
		
		return null;
	}
	
	public String getPackageNameFromStack(Integer stack, StackAction action) {
		ActivityManager.RunningTaskInfo pkg = getPackageFromStack(stack, action);
		
		return pkg != null ? pkg.topActivity.getPackageName() : null;
	}
	
	public Integer getPackageIdFromStack(Integer stack, StackAction action) {
		ActivityManager.RunningTaskInfo pkg = getPackageFromStack(stack, action);
		
		return pkg != null ? pkg.id : 0;
	}
	
	public Integer invokeCallButton() {
		Integer mode = ((AudioManager) mAudioManager.getReceiver()).getMode();
		Integer callCode = 0;
		
		if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
			callCode = KeyEvent.KEYCODE_ENDCALL;
			
		} else if (mode == AudioManager.MODE_RINGTONE) {
			callCode = KeyEvent.KEYCODE_CALL;
		}

        return callCode;
	}
	
	public Object getUserInstance() {
		return mConstructors.get("UserHandle").invoke(
				mFields.get("UserHandle.current").getValue()
		);
	}

	public void launchIntent(Intent intent) {
		if (SDK.MANAGER_MULTIUSER_VERSION > 0) {
			try {
				mMethods.get("startActivityAsUser").invoke(intent, getUserInstance());
				
			} catch (ReflectException e) {
				Log.e(TAG, e.getMessage(), e);
			}
			
		} else {
			((Context) mContext.getReceiver()).startActivity(intent);
		}
	}
	
	public void launchPackage(String packageName) {
		Intent intent = ((Context) mContext.getReceiver()).getPackageManager().getLaunchIntentForPackage(packageName);
		
		if (isKeyguardLockedAndInsecure()) {
			keyGuardDismiss();
		}
		
		if (intent != null) {
			intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
			
		} else {
			/*
			 * In case the app has been deleted after button setup
			 */
			intent = new Intent(Intent.ACTION_VIEW);
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			intent.setData(Uri.parse("market://details?id="+packageName));
		}
		
		launchIntent(intent);
	}
	
	@TargetApi(Build.VERSION_CODES.HONEYCOMB)
	public void togglePreviousApplication() {
		if (SDK.MANAGER_ACTIVITY_VERSION > 1) {
			Integer packageId = getPackageIdFromStack(1, StackAction.JUMP_HOME);
			
			if (packageId > 0) {
				((ActivityManager) mActivityManager.getReceiver()).moveTaskToFront(packageId, 0);
			}
			
		} else {
			String packageName = getPackageNameFromStack(1, StackAction.JUMP_HOME);
			
			if (packageName != null) {
				launchPackage(packageName);
			}
		}
	}
	
	public void killForegroundApplication() {
		String packageName = getPackageNameFromStack(0, StackAction.EXLUDE_HOME);
		
		if (packageName != null) {
			if(Common.debug()) Log.d(TAG, "Invoking force stop on " + packageName);
			
			try {
				if (SDK.MANAGER_MULTIUSER_VERSION > 0) {
					mMethods.get("forceStopPackage").invoke(packageName, mFields.get("UserHandle.current").getValue());

				} else {
					mMethods.get("forceStopPackage").invoke(packageName);
				}
				
			} catch (ReflectException e) {
				Log.e(TAG, e.getMessage(), e);
			}
		}
	}
	
	public void sendBroadcast(Intent intent) {
		if (SDK.MANAGER_MULTIUSER_VERSION > 0) {
			try {
				mMethods.get("sendBroadcastAsUser").invoke(intent, getUserInstance());
				
			} catch (ReflectException e) {
				Log.e(TAG, e.getMessage(), e);
			}
			
		} else {
			((Context) mContext.getReceiver()).sendBroadcast(intent);
		}
	}

    void startShortcut(String uri) {
        Intent intent = new Intent();
        try {
            intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        catch (Exception e) {
            Log.e(TAG, "Parse URI exception!"+e.getMessage(), e);
        }
        launchIntent(intent);
    }

    public void toggleFlashLight() {
		if (mTorchIntent != null) {
			if (Common.TORCH_INTENT_ACTION.equals(mTorchIntent.getAction())) {
				if(Common.debug()) Log.d(TAG, "Toggling native Torch service");
				
				((Context) mContext.getReceiver()).startService(mTorchIntent);
				
			} else {
				if(Common.debug()) Log.d(TAG, "Sending Torch Intent");
				
				sendBroadcast(mTorchIntent);
			}
		}
	}
	
	public void sendCloseSystemWindows(String reason) {
		if(Common.debug()) Log.d(TAG, "Closing all system windows");
		
		try {
			mMethods.get("closeSystemDialogs").invoke(reason);
			
		} catch (ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}
	
	public void openGlobalActionsDialog() {
		if(Common.debug()) Log.d(TAG, "Invoking Global Actions Dialog");
		
		sendCloseSystemWindows("globalactions");
		
		try {
			if (mMethods.containsKey("showGlobalActionsDialog.custom")) {
				mMethods.get("showGlobalActionsDialog.custom").invoke(true);
				
			} else {
				mMethods.get("showGlobalActionsDialog").invoke();
			}
			
		} catch (ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}
	
	public void openRecentAppsDialog() {
		if(Common.debug()) Log.d(TAG, "Invoking Recent Application Dialog");
		
		sendCloseSystemWindows("recentapps");
		
		try {
			mMethods.get("toggleRecentApps").invoke();
			
		} catch (ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}
	
	public void takeScreenshot() {
		try {
			mMethods.get("takeScreenshot").invoke();
			
		} catch (ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}
	
	public void freezeRotation(Integer orientation) {
		if (SDK.MANAGER_ROTATION_VERSION > 1) {
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
			android.provider.Settings.System.putInt(((Context) mContext.getReceiver()).getContentResolver(), android.provider.Settings.System.ACCELEROMETER_ROTATION, orientation != 1 ? 1 : 0);
		}
	}
	
	public Boolean isRotationLocked() {
        return android.provider.Settings.System.getInt(((Context) mContext.getReceiver()).getContentResolver(), android.provider.Settings.System.ACCELEROMETER_ROTATION, 0) == 0;
	}
	
	public Integer getCurrentRotation() {
		try {
			return (Integer) mMethods.get("getRotation").invoke();

		} catch (ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
		}
		
		return 0;
	}
	
	public Integer getNextRotation(Boolean backwards) {
		Integer  position = getCurrentRotation();
		
		return (position == Surface.ROTATION_90 || position == Surface.ROTATION_0) && backwards ? 270 : 
			(position == Surface.ROTATION_270 || position == Surface.ROTATION_0) && !backwards ? 90 : 0;
	}
	
	public String getHomePackage() {
		Intent intent = new Intent(Intent.ACTION_MAIN);
		intent.addCategory(Intent.CATEGORY_HOME);
		ResolveInfo res = ((Context) mContext.getReceiver()).getPackageManager().resolveActivity(intent, 0);
		
		return res.activityInfo != null && !"android".equals(res.activityInfo.packageName) ? 
				res.activityInfo.packageName : "com.android.launcher";
	}
	
	public Boolean isWakeKeyWhenScreenOff(Integer keyCode) {
		if (mMethods.get("isWakeKeyWhenScreenOff") != null) {
			return (Boolean) mMethods.get("isWakeKeyWhenScreenOff").invoke(keyCode);
		}
		
		return true;
	}
	
	public Integer fixPolicyFlags(Integer keyCode, Integer policyFlags) {
		if (!keyCode.equals(KeyEvent.KEYCODE_POWER) 
				&& !isWakeKeyWhenScreenOff(keyCode)
				&& (policyFlags & ORIGINAL.FLAG_WAKE_DROPPED) != 0) {
					policyFlags &= ~ORIGINAL.FLAG_WAKE_DROPPED;
			
		} else if ((keyCode.equals(KeyEvent.KEYCODE_POWER) || isWakeKeyWhenScreenOff(keyCode)) &&
                (policyFlags & ORIGINAL.FLAG_WAKE_DROPPED) == 0) {
			policyFlags |= ORIGINAL.FLAG_WAKE_DROPPED;
		}
		
		return policyFlags;
	}
	
	public Boolean handleScreen(ActionType actionType, Boolean isScreenOn, Long eventDownTime, Integer policyFlags) {
		/*
		 * We handle display on here, because some devices has issues
		 * when executing handlers while in deep sleep.
		 * Some times they will need a few key presses before reacting.
		 */
        if (!isScreenOn && actionType == ActionType.CLICK &&
               (((policyFlags & ORIGINAL.FLAG_WAKE_DROPPED) != 0) ||
                ((policyFlags & ORIGINAL.FLAG_WAKE) != 0))) {
            changeDisplayState(eventDownTime, true);
            if ((policyFlags & ORIGINAL.FLAG_WAKE_DROPPED) != 0) {
                return true;
            }
        }
        return false;
    }

    public Integer getActionKeyCode(String action) {
        String[] actions = Common.actionParse((Context)mContext.getReceiver(), action);
        String type = actions[0];
        Integer keyCode;
        if ("dispatch".equals(type)) {
            keyCode = Integer.parseInt(action);
        } else {
            keyCode = 0;
        }
        return keyCode;
    }

    public void handleEventAction(final String actionString) {
		/*
		 * This should always be wrapped and sent to a handler. 
		 * If this is executed directly, some of the actions will crash with the error 
		 * -> 'Can't create handler inside thread that has not called Looper.prepare()'
		 */

        String[] actions = Common.actionParse((Context)mContext.getReceiver(), actionString);
        final String type = actions[0];
        final String action = actions[1];
        mHandler.post(new Runnable() {
			public void run() {
				if ("launcher".equals(type)) {
					launchPackage(action);
					
				} else if ("custom".equals(type)) {
					if (!"disabled".equals(action)) {
						if ("torch".equals(action)) {
							toggleFlashLight();
							
						} else if ("powermenu".equals(action)) {
							openGlobalActionsDialog();	
							
						} else if ("recentapps".equals(action)) {
							openRecentAppsDialog();
							
						} else if ("screenshot".equals(action)) {
							takeScreenshot();
							
						} else if ("flipleft".equals(action)) {
							freezeRotation( getNextRotation(true) );
							
						} else if ("flipright".equals(action)) {
							freezeRotation( getNextRotation(false) );
							
						} else if ("fliptoggle".equals(action)) {
							if (isRotationLocked()) {
								Toast.makeText((Context) mContext.getReceiver(), "Rotation has been Enabled", Toast.LENGTH_SHORT).show();
								freezeRotation(1);
								
							} else {
								Toast.makeText((Context) mContext.getReceiver(), "Rotation has been Disabled", Toast.LENGTH_SHORT).show();
								freezeRotation(-1);
							}
							
						} else if ("previousapp".equals(action)) {
							togglePreviousApplication();
							
						} else if ("killapp".equals(action)) {
							killForegroundApplication();
							
						} else if ("guarddismiss".equals(action)) {
							keyGuardDismiss();
						}
					}

                } else if ("tasker".equals(type)) {
                    sendBroadcast(new TaskerIntent(action));

                } else if ("appshortcut".equals(type)) {
                    startShortcut(action);

                }
                else {
                    //"dispatch" (key code) is handled in PhoneWindowManager
                    Log.d(TAG, "Strange: unhandled action: "+action);
				}
			}
		});
 	}
}
