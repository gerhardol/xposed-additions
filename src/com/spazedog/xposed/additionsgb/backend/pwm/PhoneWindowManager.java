package com.spazedog.xposed.additionsgb.backend.pwm;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

import com.spazedog.lib.reflecttools.ReflectClass;
import com.spazedog.lib.reflecttools.utils.ReflectException;
import com.spazedog.xposed.additionsgb.Common;
import com.spazedog.xposed.additionsgb.backend.pwm.EventManager.LongPressType;
import com.spazedog.xposed.additionsgb.backend.pwm.EventManager.Priority;
import com.spazedog.xposed.additionsgb.backend.pwm.EventManager.State;
import com.spazedog.xposed.additionsgb.backend.pwm.EventManager.ActionType;
import com.spazedog.xposed.additionsgb.backend.pwm.Mediator.ORIGINAL;
import com.spazedog.xposed.additionsgb.backend.pwm.Mediator.SDK;
import com.spazedog.xposed.additionsgb.backend.pwm.Mediator.StackAction;
import com.spazedog.xposed.additionsgb.backend.service.XServiceManager;
import com.spazedog.xposed.additionsgb.backend.service.XServiceManager.XServiceBroadcastListener;
import com.spazedog.xposed.additionsgb.configs.Settings;

import de.robv.android.xposed.XC_MethodHook;

public final class PhoneWindowManager {
	public static final String TAG = PhoneWindowManager.class.getName();
	
	private XServiceManager mXServiceManager;
	private Mediator mMediator;
	private EventManager mEventManager;
	
	private Boolean mInterceptKeyCode = false;
	
	private Boolean mActiveQueueing = false;
	private Boolean mActiveDispatching = false;
	
	private final Object mQueueLock = new Object();

	private static int shortTime(){
		return (int)(SystemClock.uptimeMillis() % 10000);
	}

	/**
	 * This is a static initialization method.
	 */
	public static void init() {
		ReflectClass pwm = null;
		
		try {
			/*
			 * Start by getting the PhoneWindowManager class and
			 * create an instance of our own. 
			 */
			pwm = ReflectClass.forName("com.android.internal.policy.impl.PhoneWindowManager");
			PhoneWindowManager instance = new PhoneWindowManager();

			/*
			/*
			 * Hook the init method of the PhoneWindowManager class.
			 * This is our entry to it's process. It will be 
			 * the first thing to be invoked once the system is ready
			 * to use it. 
			 */
			pwm.inject("init", instance.hook_init);

		} catch (ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
			
			if (pwm != null) {
				/*
				 * Do not keep hooks on any kind of errors.
				 * Broken methods and such can result in system crash
				 * and boot loops. 
				 */
				pwm.removeInjections();
			}
		}
	}
	/**
	/**
	 * This method is used to hook the original PhoneWindowManager.init() method. 
	 * 
	 * Original Arguments:
	 * 		- Gingerbread: PhoneWindowManager.init(Context, IWindowManager, LocalPowerManager)
	 * 		- ICS: PhoneWindowManager.init(Context, IWindowManager, WindowManagerFuncs, LocalPowerManager)
	 * 		- JellyBean: PhoneWindowManager.init(Context, IWindowManager, WindowManagerFuncs)
	 */
	private final XC_MethodHook hook_init = new XC_MethodHook() {
		@Override
		protected final void afterHookedMethod(final MethodHookParam param) {
			/*
			 * Some Android services and such will not be ready yet.
			 * Let's wait until everything is up and running. 
			 */
			((Context) param.args[0]).registerReceiver(
				new BroadcastReceiver() {
					@Override
					public void onReceive(Context context, Intent intent) {
						/*
						 * Let's get an instance of our own Service Manager and
						 * make sure that the related service is running, before continuing.
						 */
						mXServiceManager = XServiceManager.getInstance();
						
						if (mXServiceManager != null) {
							ReflectClass pwm = null;
							
							try {
								/*
								 * Now we need to initialize our own Mediator. 
								 * And once again, do not continue without it as
								 * it contains all of our tools. 
								 */
								pwm = ReflectClass.forReceiver(param.thisObject);
								mMediator = new Mediator(pwm, mXServiceManager);
								
								if (mMediator.isReady()) {
									/*
									 * Add the remaining PhoneWindowManager hooks
									 */
									pwm.inject("interceptKeyBeforeQueueing", hook_interceptKeyBeforeQueueing);
									pwm.inject("interceptKeyBeforeDispatching", hook_interceptKeyBeforeDispatching);
									pwm.inject("performHapticFeedbackLw", hook_performHapticFeedbackLw);
									
									if (SDK.SAMSUNG_FEEDBACK_VERSION > 0) {
										ReflectClass spwm = ReflectClass.forName("com.android.internal.policy.impl.sec.SamsungPhoneWindowManager");
										spwm.inject("performSystemKeyFeedback", hook_performHapticFeedbackLw);
									}
									/*
									/*
									 * Add hooks to the ViewConfiguration class for this process,
									 * allowing us to control key timeout values that will affect the original class.
									 */
									ReflectClass wc = ReflectClass.forName("android.view.ViewConfiguration");

									wc.inject("getLongPressTimeout", hook_viewConfigTimeouts);
									wc.inject("getGlobalActionKeyTimeout", hook_viewConfigTimeouts);
									/*
									/*
									 * Create key class instances for key control
									 */
									mEventManager = new EventManager(mXServiceManager);
									/*
									/*
									 * Add listener to receive broadcasts from the XService
									 */
									mXServiceManager.addBroadcastListener(listener_XServiceBroadcast);
								}

							} catch (Throwable e) {
								Log.e(TAG, e.getMessage(), e);
								
								if (pwm != null) {
									/*
									 * On error, disable this part of the module.
									 */
									pwm.removeInjections();
								}
							}
							
						} else {
							Log.e(TAG, "XService has not been started", null);
						}
					}
					
				}, new IntentFilter("android.intent.action.BOOT_COMPLETED")
			);
		}
	};
	/**
	/**
	 * A listener that is used used to receive key intercept requests from the settings part of the module.
	 */
	private final XServiceBroadcastListener listener_XServiceBroadcast = new XServiceBroadcastListener() {
		@Override
		public void onBroadcastReceive(String action, Bundle data) {
			if (action.equals("keyIntercepter:enable")) {
				mInterceptKeyCode = true;
				
			} else if (action.equals("keyIntercepter:disable")) {
				mInterceptKeyCode = false;
			}
		}
	};
	/**
	/**
	 * This does not really belong to the PhoneWindowManager class. 
	 * It is used as a small hack to change some internal method behavior, without
	 * affecting more than the original PhoneWindowManager process. 
	 * 
	 * Some ROM's uses the original implementations to set timeout on some handlers. 
	 * Like long press timeout for the power key when displaying the power menu. 
	 * But since we cannot change code inside original methods in order to change this timeout, 
	 * we instead change the output of the methods delivering that timeout value.
	 * 
	 * Note: The original methods are called in register event, this must not be active.
	 * 
	 * Original Implementations:
	 * 		- android.view.ViewConfiguration.getLongPressTimeout
	 * 		- android.view.ViewConfiguration.getGlobalActionKeyTimeout
	 */
	private final XC_MethodHook hook_viewConfigTimeouts = new XC_MethodHook() {
		@Override
		protected final void afterHookedMethod(final MethodHookParam param) {
			if (mEventManager.getState() == State.INVOKED && mEventManager.isDownEvent()) {
				if (mEventManager.getLongPress() == LongPressType.DEFAULT_ACTION) {
					//The timeout has already occurred when default is dispatched
					param.setResult(10);
				}
				else if (mEventManager.getLongPress() == LongPressType.CUSTOM_ACTION) {
					//The timeout is longer than usual, handled after the first injected key
					param.setResult(mEventManager.getPressTimeout()*2+10);
				}
			}
		}
	};
	/**
	/**
	 * This hook is used to make all of the preparations of key handling
	 * 
	 * Original Arguments
	 * 		- Gingerbread: PhoneWindowManager.interceptKeyBeforeQueueing(Long whenNanos, Integer action, Integer flags, Integer keyCode, Integer scanCode, Integer policyFlags, Boolean isScreenOn)
	 * 		- ICS & Above: PhoneWindowManager.interceptKeyBeforeQueueing(KeyEvent event, Integer policyFlags, Boolean isScreenOn)
	 */
	protected final XC_MethodHook hook_interceptKeyBeforeQueueing = new XC_MethodHook() {
		@Override
		protected final void beforeHookedMethod(final MethodHookParam param) {
			synchronized(mQueueLock) {
				mActiveQueueing = true;

				Integer methodVersion = Mediator.SDK.METHOD_INTERCEPT_VERSION;
				KeyEvent keyEvent = methodVersion == 1 ? null : (KeyEvent) param.args[0];
				Integer action = (Integer) (methodVersion == 1 ? param.args[1] : keyEvent.getAction());
				Integer policyFlagsPos = methodVersion == 1 ? 5 : 1;
				Integer policyFlags = (Integer) (param.args[policyFlagsPos]);
				Integer keyCode = (Integer) (methodVersion == 1 ? param.args[3] : keyEvent.getKeyCode());
				Integer repeatCount = methodVersion == 1 ? 0 : keyEvent.getRepeatCount();
				Boolean isScreenOn = (Boolean) (methodVersion == 1 ? param.args[6] : param.args[2]);
				Boolean down = action == KeyEvent.ACTION_DOWN;
				String tag = TAG + "#Queueing/" + (down ? "Down " : "Up ") + keyCode + ":" + shortTime() + "(" + mEventManager.getTapCount() + "," + repeatCount+ "):";

				/*
				/*
				 * Using KitKat work-around from the InputManager Hook
				 */
				Boolean isInjected = Mediator.SDK.MANAGER_HARDWAREINPUT_VERSION > 1 ? 
						(((KeyEvent) param.args[0]).getFlags() & Mediator.ORIGINAL.FLAG_INJECTED) != 0 : (policyFlags & Mediator.ORIGINAL.FLAG_INJECTED) != 0;
				/*
				/*
				 * The module should not handle injected keys. 
				 * First of all, we inject keys our self and would create a loop. 
				 * Second, some software buttons use injection, and we don't remap software keys.
				 */
				if (isInjected) {
					if (down && repeatCount > 0) {
						/*
						 * Normally repeated events will not continue to invoke this method. 
						 * But it seams that repeating an event using injection will. On most devices
						 * the original methods themselves seams to be handling this just fine, but a few 
						 * stock ROM's are treating these as both new and repeated events. 
						 */
						if(Common.debug()) Log.d(tag, "Injected repeated key, no event change");
						param.setResult(Mediator.ORIGINAL.QUEUEING_ALLOW);
						
					} else if ((policyFlags & Mediator.ORIGINAL.FLAG_INJECTED) != 0) {
						/*
						 * Some ROM's disables features on injected keys. So let's remove the flag.
						 */
						param.args[policyFlagsPos] = policyFlags & ~Mediator.ORIGINAL.FLAG_INJECTED;
					}
				/*
				/*
				 * No need to do anything if the settings part of the module
				 * has asked for the keys. However, do make sure that the screen is on.
				 * The display could have been auto turned off while in the settings remap part.
				 * We don't want to create a situation where users can't turn the screen back on.
				 */
				} else if (mInterceptKeyCode && isScreenOn) {
					if (down) {
						mMediator.performHapticFeedback(keyEvent, HapticFeedbackConstants.VIRTUAL_KEY, policyFlags);
						
					} else if (mMediator.validateDeviceType(keyEvent == null ? keyCode : keyEvent)) {
						Bundle bundle = new Bundle();
						bundle.putInt("keyCode", keyCode);
						/*
						/*
						 * Send the key back to the settings part
						 */
						mXServiceManager.sendBroadcast("keyIntercepter:keyCode", bundle);
					}
					
					param.setResult(Mediator.ORIGINAL.QUEUEING_REJECT);
					
				} else {
					/*

					 * For those missing (like Omate TrueSmart) this is kind of a replacement.
					 */
					mMediator.powerHardResetTimer(keyCode, down);
					
					if (mEventManager.registerKey(keyCode, down, mMediator.fixPolicyFlags(keyCode, policyFlags))) {
						if(Common.debug()) Log.d(tag, "Starting a new event");
						/*
						/*
						 * Check to see if this is a new event (Which means not a continued tap event or a general key up event).
						 */
						/*
						/*
						 * Make sure that we have a valid and supported device type
						 */
						if (mMediator.validateDeviceType(keyEvent == null ? keyCode : keyEvent)) {
							/*
							 * Prepare the event information for this key or key combo.
							 */
							mEventManager.registerEvent(mMediator.getPackageNameFromStack(0, StackAction.INCLUDE_HOME), mMediator.isKeyguardLocked(), isScreenOn);
							/*
							/*
							 * If the screen is off, it's a good idea to poke the device out of deep sleep. 
							 */
							if (!isScreenOn) {								
								mMediator.pokeUserActivity(mEventManager.getDownTime(), false);
							}
							
						} else {
							if(Common.debug()) Log.d(tag, "The key is not valid, skipping...");
							/*
							/*
							 * Don't handle this event
							 */
							mEventManager.cancelEvent(true);
							
							return;
						}
						
					} else if(Common.debug()) {
						 Log.d(tag, "Continuing ongoing event");
					}
					
					if (down) {
						mMediator.performHapticFeedback(keyEvent == null ? keyCode : keyEvent, HapticFeedbackConstants.VIRTUAL_KEY, policyFlags);
					}
					
					if (mEventManager.getState() != State.CANCELED && mEventManager.getEventKey(keyCode) != null) {
						if(Common.debug()) Log.d(tag, "Passing the event to the queue");
						param.setResult(Mediator.ORIGINAL.QUEUEING_ALLOW);
						
					} else {
						if(Common.debug()) Log.d(tag, "The event has been canceled, skipping");
					}
				}
			}
		}
		
		@Override
		protected final void afterHookedMethod(final MethodHookParam param) {
			mActiveQueueing = false;
		}
	};

	
	/**

		if (resetAtPowerPress > 0) {

			final long eventTime = mEventManager.getEventTime();
			final int delay = resetAtPowerPress * 1000;

			//Haptic feedback that reboot is pending
			mMediator.mHandler.postDelayed(new Runnable() {
				public void run() {
					if (mEventManager.isDownEvent() && mEventManager.getEventTime() == eventTime && mEventManager.getLastQueuedKey() == keyCode) {
						mMediator.performPowerPressNotification();
					}
				}
			}, mEventManager.getPressTimeout()+1000);

			mMediator.mHandler.postDelayed(new Runnable() {
				public void run() {
					if (mEventManager.isDownEvent() && mEventManager.getEventTime() == eventTime && mEventManager.getLastQueuedKey() == keyCode) {
						mMediator.performPowerPressReset(resetAtPowerPress);
					}
				}
			}, delay);
		}
	}

	/**
	 * This hook is used to do the actual handling of the keys
	 * 
	 * Original Arguments
	 * 		- Gingerbread: PhoneWindowManager.interceptKeyBeforeDispatching(WindowState win, Integer action, Integer flags, Integer keyCode, Integer scanCode, Integer metaState, Integer repeatCount, Integer policyFlags)
	 * 		- ICS & Above: PhoneWindowManager.interceptKeyBeforeDispatching(WindowState win, KeyEvent event, Integer policyFlags)
	 */	
	protected XC_MethodHook hook_interceptKeyBeforeDispatching = new XC_MethodHook() {
		@TargetApi(Build.VERSION_CODES.HONEYCOMB_MR1)
		@Override
		protected final void beforeHookedMethod(final MethodHookParam param) {
			mActiveDispatching = true;

			Integer methodVersion = Mediator.SDK.METHOD_INTERCEPT_VERSION;
			KeyEvent keyEvent = methodVersion == 1 ? null : (KeyEvent) param.args[1];
			Integer keyCode = (Integer) (methodVersion == 1 ? param.args[3] : keyEvent.getKeyCode());
			Integer action = (Integer) (methodVersion == 1 ? param.args[1] : keyEvent.getAction());
			Integer policyFlagsPos = methodVersion == 1 ? 7 : 2;
			Integer policyFlags = (Integer) (param.args[policyFlagsPos]);
			Integer repeatCount = (Integer) (methodVersion == 1 ? param.args[6] : keyEvent.getRepeatCount());
			Boolean down = action == KeyEvent.ACTION_DOWN;
			EventKey key = mEventManager.getEventKey(keyCode);
			String tag = TAG + "#Dispatching/" + (down ? "Down " : "Up ") + keyCode + ":" + shortTime() + "(" + mEventManager.getTapCount() + "," + repeatCount+ "):";

			if (Common.debug()) {
				Log.d(tag, "Getting event with state " + mEventManager.getState().name() + " which is an " + (mEventManager.getEventKey(keyCode) != null ? "ongoing" : "non-ongoing") + " event");
			}
			/*
			/*
			 * Using KitKat work-around from the InputManager Hook
			 */
			Boolean isInjected = Mediator.SDK.MANAGER_HARDWAREINPUT_VERSION > 1 ? 
					(((KeyEvent) param.args[1]).getFlags() & Mediator.ORIGINAL.FLAG_INJECTED) != 0 : (policyFlags & Mediator.ORIGINAL.FLAG_INJECTED) != 0;
			
			//Any up key interrupts ongoing key codes
			//"Default event" (not injected) keys could be handled on the up event, but there is a workaround for some ROMs and combi-events
			if (!down && mEventManager.hasOngoingKeyCodes()) {
				synchronized(mQueueLock) {
					final Integer[] ongoing = mEventManager.clearOngoingKeyCodes(true);
					for (int i=0; i < ongoing.length; i++) {
						if(Common.debug()) Log.d(tag, "Releasing ongoing key " + ongoing[i]);

						Integer policyFlags2 = (ongoing[i] == keyCode) ? policyFlags : key == null ? 0 : key.getPolicFlags();
						mMediator.injectInputEvent(ongoing[i], KeyEvent.ACTION_UP, 0L, 0L, 0, policyFlags2);
					}
				}
			}

			if (isInjected) {
				if (down && key != null && mEventManager.isDownEvent() && 
						mEventManager.getLongPress() != LongPressType.NONE && mEventManager.hasOngoingKeyCodes(keyCode)) {
					if(Common.debug()) Log.d(tag, "Injecting a new repeat " + repeatCount);

					final int longLongPressDelay = 2 * mEventManager.getPressTimeout(); //Wait 2 times normal long press
					Integer curTimeout = (repeatCount == 0) ? (mEventManager.getLongPress() == LongPressType.CUSTOM_ACTION ? longLongPressDelay : 0) :
						SDK.VIEW_CONFIGURATION_VERSION > 1 ? ViewConfiguration.getKeyRepeatDelay() : 50;
						final long eventTime = mEventManager.getEventTime();
					
						do {
							try {
								Thread.sleep(1);
							
						} catch (Throwable e) {}
						
							curTimeout -= 1;
						
						} while (mEventManager.isDownEvent() && mEventManager.getEventTime() == eventTime && key.getKeyCode() == keyCode && curTimeout > 0);
					
						synchronized(mQueueLock) {
							//Note: There is a possibility that the ROM inserts repeats when the timeout expires, why hook_viewConfigTimeouts
							//must set slightly longer times than used here
							//The full solution is to save the event time for for the repeat event and check that before inserting 

							if (mEventManager.getState() == State.INVOKED && mEventManager.isDownEvent() && mEventManager.getEventTime() == eventTime && key.getKeyCode() == keyCode) {
								mMediator.injectInputEvent(key.getKeyCode(), KeyEvent.ACTION_DOWN, mEventManager.getDownTime(), mEventManager.getEventTime(), repeatCount+1, key.getPolicFlags());
							}
						}
						if (curTimeout <= 0 && repeatCount == 0 && mEventManager.getLongPress() == LongPressType.CUSTOM_ACTION) {
							mMediator.performLongPressFeedback();
						}
				}

				if ((policyFlags & Mediator.ORIGINAL.FLAG_INJECTED) != 0) {
					param.args[policyFlagsPos] = policyFlags & ~Mediator.ORIGINAL.FLAG_INJECTED;
				}
				
				return;
				
			} else if (down && key != null && mEventManager.getState() == State.ONGOING && mEventManager.getLastQueuedKey() == keyCode) {
				if(Common.debug()) Log.d(tag, "Waiting on long press timeout");
				
				Integer pressTimeout = mEventManager.getPressTimeout();
				
				do {
					try {
						Thread.sleep(1);

					} catch (Throwable e) {}

					pressTimeout -= 1;
					
				} while (mEventManager.isDownEvent() && mEventManager.getLastQueuedKey() == keyCode && pressTimeout > 0);
				
				synchronized(mQueueLock) {
					if (mEventManager.getState() == State.ONGOING && mEventManager.isDownEvent() && mEventManager.getLastQueuedKey() == keyCode) {
						String eventAction = mEventManager.getAction(ActionType.PRESS);
						if (Common.debug()) Log.d(tag, shortTime() + " Invoking long press action: " + eventAction);

						final EventKey keyLong;
						final boolean defaultEvent;
						final boolean keyAction;
						if (eventAction != null) {
							defaultEvent = false;
							//custom long press action
							//TODO: Implementation done to give minimal diff, should be rewritten
							final String type = Common.actionType(eventAction);
							keyLong = mEventManager.getEventKey(Priority.INVOKED);
							keyLong.mPolicyFlags = 0;
							//TODO: This handling should probably set the keycode already when parsing the preferences
							if ("dispatch".equals(type)) {
								keyAction = true;
								//This is a keyevent, handled mostly as default
								keyLong.mKeyCode = Integer.parseInt(eventAction);
								if (keyLong.mKeyCode == KeyEvent.KEYCODE_POWER) {
									//set flag, to handle in handleKeyAction()
									keyLong.mPolicyFlags = ORIGINAL.FLAG_WAKE_DROPPED;
								}
								mEventManager.setLongPress(LongPressType.CUSTOM_ACTION);
								mMediator.performLongPressFeedback();
								//Set action to null, still handling in handleKeyAction()
								eventAction = null;
							} else {
								keyAction = false;
							}
						} else {
							defaultEvent = true;
							keyLong = key;
							keyAction = true;
							mEventManager.setLongPress(LongPressType.DEFAULT_ACTION);
						}
							
						mEventManager.invokeEvent();
						//TODO: (suggest snippet waking up to be separated to injectInputEvent)
						mMediator.handleKeyAction(eventAction, mEventManager.getTapCount() == 0, mEventManager.isScreenOn(), mEventManager.isCallButtonEvent(), mEventManager.getDownTime(), keyLong.getPolicFlags());
							
						if (keyAction) {
							//TODO: Handle tapCount() (or ignore multi actions) for default events. What is default for multiple taps?
							//The long-press flag will be set next the key is handled by this function
							//For default keys, this is instant, for user key the delay is long-long
							//There is therefore a race condition that the default long-press is never set
							
							if (defaultEvent && mEventManager.isCombiEvent()) {
								if(Common.debug()) Log.d(tag, "Injecting primary combo event");

								final EventKey parentKey = mEventManager.getParentEventKey(keyLong.mKeyCode);

								mEventManager.addOngoingKeyCode(parentKey.getKeyCode());
								mMediator.injectInputEvent(parentKey.getKeyCode(), KeyEvent.ACTION_DOWN, mEventManager.getDownTime(), mEventManager.getEventTime(), 0, parentKey.getPolicFlags());
							}
							mEventManager.addOngoingKeyCode(keyLong.mKeyCode);
							mMediator.injectInputEvent(keyLong.mKeyCode, KeyEvent.ACTION_DOWN, mEventManager.getDownTime(), mEventManager.getEventTime(), 0, keyLong.getPolicFlags());

							if (defaultEvent) {
								/*
							/*
								 * The first one MUST be dispatched throughout the system.
								 * Applications can ONLY start tracking from the original event object.
								 */
								if(Common.debug()) Log.d(tag, "Passing event to the dispatcher");
							
								param.setResult(Mediator.ORIGINAL.DISPATCHING_ALLOW); 
							
								return;
							}
						}
					}
				}

			} else if (!down && key != null && mEventManager.getState() == State.ONGOING && mEventManager.getLastQueuedKey() == keyCode) {
				if (mEventManager.hasMoreAction(ActionType.CLICK, true)) {
					if(Common.debug()) Log.d(tag, "Waiting on tap timeout");
					
					Integer tapTimeout = mEventManager.getTapTimeout();
					
					do {
						try {
							Thread.sleep(1);

						} catch (Throwable e) {}

						tapTimeout -= 1;
						
					} while (!mEventManager.isDownEvent() && mEventManager.getLastQueuedKey() == keyCode && tapTimeout > 0);
				}
					
				synchronized(mQueueLock) {
					if (mEventManager.getState() == State.ONGOING && !mEventManager.isDownEvent() && mEventManager.getLastQueuedKey() == keyCode) {
						final String eventAction = mEventManager.getAction(ActionType.CLICK);
						if (Common.debug()) Log.d(tag, shortTime() + " Invoking click action: " + eventAction);

						mEventManager.invokeEvent();
						
						if (!mMediator.handleKeyAction(eventAction, false, mEventManager.isScreenOn(), mEventManager.isCallButtonEvent(), mEventManager.getDownTime(), key.getPolicFlags())) {
							//No custom click action available, invoking default actions
							
							if (mEventManager.isCombiEvent()) {
								if(Common.debug()) Log.d(tag, "Injecting primary combo event");
								
								EventKey parentKey = mEventManager.getParentEventKey(keyCode);
								
								//TODO: Handle tapCount() (or ignore for primary)
								mEventManager.addOngoingKeyCode(parentKey.getKeyCode());
								mMediator.injectInputEvent(parentKey.getKeyCode(), KeyEvent.ACTION_DOWN, mEventManager.getDownTime(), mEventManager.getEventTime(), 0, parentKey.getPolicFlags());
							}
							
							for (int i=0; i <= mEventManager.getTapCount(); i++) {
								if(Common.debug()) Log.d(tag, "Injecting default event");
								
								mMediator.injectInputEvent(keyCode, KeyEvent.ACTION_MULTIPLE, mEventManager.getDownTime(), mEventManager.getEventTime(), 0, policyFlags);
							}
						}
					}
				}
			} else if (mEventManager.getState() == State.PENDING || key == null) {
				if(Common.debug()) Log.d(tag, "Not an active key, returning it to the original dispatcher...");
				return;
			}
			
			if(Common.debug()) Log.d(tag, "Sending to dispatching: " + shortTime());
			
			param.setResult(Mediator.ORIGINAL.DISPATCHING_REJECT);
		}
		
		@Override
		protected final void afterHookedMethod(final MethodHookParam param) {
			mActiveDispatching = false;
		}
	};
	
	protected XC_MethodHook hook_performHapticFeedbackLw = new XC_MethodHook() {
		@Override
		protected final void beforeHookedMethod(final MethodHookParam param) {
			if (mActiveQueueing || mActiveDispatching) {
				if (param.method.getName().equals("performSystemKeyFeedback") || (Integer) param.args[1] == HapticFeedbackConstants.VIRTUAL_KEY) {
					param.setResult(true);
				}
			}
		}
	};
}
