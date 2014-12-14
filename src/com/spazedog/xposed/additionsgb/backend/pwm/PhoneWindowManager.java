package com.spazedog.xposed.additionsgb.backend.pwm;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

import com.spazedog.lib.reflecttools.ReflectClass;
import com.spazedog.lib.reflecttools.utils.ReflectException;
import com.spazedog.xposed.additionsgb.Common;
import com.spazedog.xposed.additionsgb.backend.pwm.EventKey.EventKeyType;
import com.spazedog.xposed.additionsgb.backend.pwm.EventManager.State;
import com.spazedog.xposed.additionsgb.backend.pwm.iface.IEventMediator.ActionType;
import com.spazedog.xposed.additionsgb.backend.pwm.iface.IMediatorSetup.ORIGINAL;
import com.spazedog.xposed.additionsgb.backend.pwm.iface.IMediatorSetup.SDK;
import com.spazedog.xposed.additionsgb.backend.service.XServiceManager;
import com.spazedog.xposed.additionsgb.backend.service.XServiceManager.XServiceBroadcastListener;

import de.robv.android.xposed.XC_MethodHook;

public final class PhoneWindowManager {
	public static final String TAG = PhoneWindowManager.class.getName();
	
	private XServiceManager mXServiceManager;
	private EventManager mEventManager;
	
	private Boolean mInterceptKeyCode = false;
	
	private Boolean mActiveQueueing = false;
	private Boolean mActiveDispatching = false;
	
	private final Object mQueueLock = new Object();

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
								mEventManager = new EventManager(pwm, mXServiceManager);
								
								if (mEventManager.isReady()) {
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
									 * Add hooks to the ViewConfiguration class for this process,
									 * allowing us to control key timeout values that will affect the original class.
									 */
									ReflectClass wc = ReflectClass.forName("android.view.ViewConfiguration");
		
									wc.inject("getLongPressTimeout", hook_viewConfigTimeouts);
									wc.inject("getGlobalActionKeyTimeout", hook_viewConfigTimeouts);
									
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
	 * This does not really belong to the PhoneWindowManager class. 
	 * It is used as a small hack to change some internal method behavior, without
	 * affecting more than the original PhoneWindowManager process. 
	 * 
	 * Some ROM's uses the original implementations to set timeout on some handlers. 
	 * Like long press timeout for the power key when displaying the power menu. 
	 * But since we cannot change code inside original methods in order to change this timeout, 
	 * we instead change the output of the methods delivering that timeout value.
	 * This timeout should be just longer than the inserted timeouts
	 * 
	 * Original Implementations:
	 * 		- android.view.ViewConfiguration.getLongPressTimeout
	 * 		- android.view.ViewConfiguration.getGlobalActionKeyTimeout
	 */
	private final XC_MethodHook hook_viewConfigTimeouts = new XC_MethodHook() {
		@Override
		protected final void afterHookedMethod(final MethodHookParam param) {
			if (mEventManager.hasState(State.REPEATING) && mEventManager.isDownEvent()) {
				if (mEventManager.getInvokedDefault()) {
					//The timeout has already occurred when default is dispatched
					param.setResult(10);
				}
				else {
					//The timeout is longer than usual, handled after the first injected key
					param.setResult(10 + 2*mEventManager.getLongLongPressDelay());
				}
			}
		}
	};
	
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
			Integer methodVersion = SDK.METHOD_INTERCEPT_VERSION;
            KeyEvent keyEvent;
            Object keyObject;
            if (methodVersion > 1) {
                keyEvent = (KeyEvent) param.args[0];
                keyObject = keyEvent;
            } else {
                Integer keyCode = (Integer)param.args[3];
                Integer action = (Integer)param.args[1];
                Long eventTime = android.os.SystemClock.uptimeMillis();
                Long downTime = ((Long) param.args[0]) / 1000 / 1000;
                Integer repeatCount = 0;
                Integer metaState = 0;
                keyEvent = new KeyEvent(downTime,eventTime,action,keyCode,repeatCount,metaState);
                keyObject = keyCode;
            }
            Integer policyFlagsPos = methodVersion == 1 ? 5 : 1;
            Integer policyFlags = (Integer) (param.args[policyFlagsPos]);
            Boolean isScreenOn = (Boolean) (methodVersion == 1 ? param.args[6] : param.args[2]);

			Integer keyCode = keyEvent.getKeyCode();
			Integer action = keyEvent.getAction();
			Integer repeatCount = keyEvent.getRepeatCount();
			Boolean down = action == KeyEvent.ACTION_DOWN;
			String tag = TAG + "#Queueing/" + (down ? "Down " : "Up ") + keyCode + "(" + mEventManager.getTapCount() + "," + repeatCount+ "):";

			if (down && !mEventManager.hasState(State.PENDING)) {
				try {
					Thread.sleep(1);
					
				} catch (InterruptedException e) {}
			}
			
			synchronized(mQueueLock) {
				mActiveQueueing = true;
				
				/*
				 * Using KitKat work-around from the InputManager Hook
				 */
				Boolean isInjected = SDK.MANAGER_HARDWAREINPUT_VERSION > 1 ? 
						(((KeyEvent) param.args[0]).getFlags() & ORIGINAL.FLAG_INJECTED) != 0 : (policyFlags & ORIGINAL.FLAG_INJECTED) != 0;
				
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
						param.setResult(ORIGINAL.QUEUEING_ALLOW);
						
					} else if ((policyFlags & ORIGINAL.FLAG_INJECTED) != 0) {
						/*
						 * Some ROM's disables features on injected keys. So let's remove the flag.
						 */
						param.args[policyFlagsPos] = policyFlags & ~ORIGINAL.FLAG_INJECTED;
					}
					
				/*
				 * No need to do anything if the settings part of the module
				 * has asked for the keys. However, do make sure that the screen is on.
				 * The display could have been auto turned off while in the settings remap part.
				 * We don't want to create a situation where users can't turn the screen back on.
				 */
				} else if (mInterceptKeyCode && isScreenOn) {
					if (down) {
						mEventManager.performHapticFeedback(keyEvent, HapticFeedbackConstants.VIRTUAL_KEY, policyFlags);
						
					} else if (mEventManager.validateDeviceType(keyObject)) {
						Bundle bundle = new Bundle();
						bundle.putInt("keyCode", keyCode);
						
						/*
						 * Send the key back to the settings part
						 */
						mXServiceManager.sendBroadcast("keyIntercepter:keyCode", bundle);
					}
					
					param.setResult(ORIGINAL.QUEUEING_REJECT);
					
				} else if (mEventManager.validateDeviceType(keyObject)) {
					/*
					 * Most ROM reboots after holding Power for 8-12s.
					 * For those missing (like Omate TrueSmart) this is kind of a replacement.
					 */
					mEventManager.powerHardResetTimer(keyCode, down, mEventManager.getPressTimeout());

					if (mEventManager.registerKey(keyEvent, isScreenOn, policyFlags)) {
						if(Common.debug()) Log.d(tag, "Starting a new event");
						
						/*
						 * If the screen is off, it's a good idea to poke the device out of deep sleep. 
						 */
						if (!isScreenOn) {
							mEventManager.pokeUserActivity(mEventManager.getEventTime(), false);
						}
						
					} else {
						if (!mEventManager.isHandledKey(keyCode)) {
							if(Common.debug()) Log.d(tag, "Unconfigured key, no action");
							return;
						}
						if(Common.debug()) Log.d(tag, "Continuing ongoing event");
					}
					
					if (down) {
						mEventManager.performHapticFeedback(keyEvent, HapticFeedbackConstants.VIRTUAL_KEY, policyFlags);
					}
					
					if(Common.debug()) Log.d(tag, "Passing the event to the queue (" + mEventManager.mState.name() + ")");
					
					param.setResult(ORIGINAL.QUEUEING_ALLOW);
				}
			}
		}
		
		@Override
		protected final void afterHookedMethod(final MethodHookParam param) {
			mActiveQueueing = false;
		}
	};
	
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

            Integer methodVersion = SDK.METHOD_INTERCEPT_VERSION;
            KeyEvent keyEvent = methodVersion == 1 ? null : (KeyEvent) param.args[1];
            Integer keyCode = (Integer) (methodVersion == 1 ? param.args[3] : keyEvent.getKeyCode());
            Integer action = (Integer) (methodVersion == 1 ? param.args[1] : keyEvent.getAction());
            Integer policyFlagsPos = methodVersion == 1 ? 7 : 2;
            Integer policyFlags = (Integer) (param.args[policyFlagsPos]);
            Integer repeatCount = (Integer) (methodVersion == 1 ? param.args[6] : keyEvent.getRepeatCount());
            Boolean down = action == KeyEvent.ACTION_DOWN;

            EventKey key = mEventManager.getKey(keyCode);
            String tag = TAG + "#Dispatching/" + (down ? "Down " : "Up ") + keyCode + "(" + mEventManager.getTapCount() + "," + repeatCount + "):";

            if (!down && mEventManager.hasState(State.REPEATING) && mEventManager.isHandledKey(keyCode, EventKeyType.INVOKED)) {
                //key up stop repeats
                //Do this before Unconfigured key check, as a safety check
                synchronized (mQueueLock) {
                    if (mEventManager.hasState(State.REPEATING)) {
                        //Release invoked long press keys on any up key
                        for (EventKey ikey : mEventManager.getKeyList(EventKeyType.INVOKED)) {
                            if (ikey.isUsed()) {
                                ikey.release(null);
                            }
                        }
                        mEventManager.setState(State.INVOKED);
                    }
                }
            }

            if (key == null) {
                if (Common.debug()) Log.d(tag, "Unconfigured key, not handling");
                return;
            }

        	/*
			 * Using KitKat work-around from the InputManager Hook
			 */
            Boolean isInjected = SDK.MANAGER_HARDWAREINPUT_VERSION > 1 ?
                    (((KeyEvent) param.args[1]).getFlags() & ORIGINAL.FLAG_INJECTED) != 0 : (policyFlags & ORIGINAL.FLAG_INJECTED) != 0;

            //Default action longpress has no injected flag
            //Boolean isLongPress = down && mEventManager.hasState(State.REPEATING) && mEventManager.getInvokedDefault();
            if (isInjected || mEventManager.hasState(State.REPEATING)) {
				/*
				 * When we disallow applications from getting the event, we also disable repeats. 
				 * This is a hack where we create a controlled injection loop to simulate repeats. 
				 * 
				 * If we did not have to support GB, then we could have just returned the timeout to force repeat without global dispatching. 
				 * But since we have GB to think about, this is the best solution. 
				 */
                if (down && mEventManager.hasState(State.REPEATING) &&
                        (mEventManager.getInvokedDefault() && mEventManager.isHandledKey(keyCode, EventKeyType.DEVICE) ||
                        !mEventManager.getInvokedDefault() && mEventManager.isHandledKey(keyCode, EventKeyType.INVOKED))) {
                    if (Common.debug()) Log.d(tag, "Injecting a new repeat " + repeatCount);

                    Integer curTimeout = (repeatCount == 0) ? (mEventManager.getInvokedDefault() ? 0 : mEventManager.getLongLongPressDelay()) :
                            SDK.VIEW_CONFIGURATION_VERSION > 1 ? ViewConfiguration.getKeyRepeatDelay() : 50;

                    mEventManager.waitForLongpressChange(curTimeout);
                    Boolean continueEvent = false;
                    synchronized (mQueueLock) {
                        //Check state, ignore timeout
                        if (mEventManager.hasState(State.REPEATING)) {
                            continueEvent = true;
                            key.invoke(keyEvent);
                        }
                    }
                    if (continueEvent && repeatCount == 0 && !mEventManager.getInvokedDefault()) {
                        mEventManager.performLongPressFeedback();
                    }
                }

                if ((policyFlags & ORIGINAL.FLAG_INJECTED) != 0) {
                    param.args[policyFlagsPos] = policyFlags & ~ORIGINAL.FLAG_INJECTED;
                }

            } else if (mEventManager.isHandledKey(keyCode, EventKeyType.DEVICE)) {
                if (mEventManager.hasState(State.ONGOING)) {
                    if (down) {
                        if (Common.debug()) Log.d(tag, "Waiting on long press timeout");
                        KeyEvent origKeyKeyEvent = key.getKeyEvent();
                        Boolean timeoutExpired = mEventManager.waitForPressChange(key, origKeyKeyEvent);

                        synchronized (mQueueLock) {
                            //Continue if state is still OnGoing and timer released
                            //or if this is default action (other combo key released)
                            Boolean isDefault = mEventManager.getInvokedDefault() || !origKeyKeyEvent.equals(key.getKeyEvent());
                            if (timeoutExpired && mEventManager.hasState(State.ONGOING) || isDefault) {
                                String eventAction = mEventManager.getAction(ActionType.PRESS);
                                isDefault = isDefault || eventAction == null;
                                if (Common.debug())
                                    Log.d(tag, "Invoking press action: " + (isDefault ? "<default>" : eventAction));

                                //_If_ this is a key, do long press handling
                                mEventManager.handleFeedbackAndScreen(eventAction, ActionType.PRESS, mEventManager.getTapCount(), mEventManager.isScreenOn(), mEventManager.getEventTime(), 0);

                                if (isDefault) {
									/*
									 * The first one MUST be dispatched throughout the system.
									 * Applications can ONLY start tracking from the original event object.
									 */
                                    if (Common.debug())
                                        Log.d(tag, "Passing event to the dispatcher");

                                    key.setKeyPressSent(true);
                                    param.setResult(ORIGINAL.DISPATCHING_ALLOW);

                                    if (origKeyKeyEvent.equals(key.getKeyEvent())) {
                                        if (mEventManager.getInvokedDefault()) {
                                            //This was the second in a combo, already released
                                            if (mEventManager.hasState(State.INVOKED)) {
                                                key.release(keyEvent);
                                            }
                                        } else {
                                            mEventManager.setInvokedDefault();
                                            mEventManager.setState(State.REPEATING);
                                        }
                                    } else {
                                        //Already moved on
                                        if(timeoutExpired) {
                                            mEventManager.injectInputEvent(keyEvent, KeyEvent.ACTION_DOWN, 1, policyFlags);
                                        }
                                        mEventManager.injectInputEvent(keyEvent, KeyEvent.ACTION_UP, 0, policyFlags);
                                    }
                                    return;

                                } else {
                                    Boolean isKey = mEventManager.handleKeyAction(eventAction, ActionType.PRESS, mEventManager.isCallButton(), 0, mEventManager);
                                    //mEventManager.setAllKeysReleased(EventKeyType.DEVICE);
                                    if (isKey) {
                                        mEventManager.setState(State.REPEATING);
                                    } else {
                                        mEventManager.setState(State.INVOKED);
                                    }
                                }
                            } else {
                                if (Common.debug())
                                    Log.d(tag, "No action" + " " + timeoutExpired + " " + mEventManager.mState + " " + origKeyKeyEvent.equals(key.getKeyEvent()));
                            }
                        }

                    } else {
                        Boolean timeoutExpired;
                        KeyEvent origKeyKeyEvent = key.getKeyEvent();
                        if (mEventManager.hasMoreActions()) {
                            if (Common.debug()) Log.d(tag, "Waiting on tap timeout");

                            timeoutExpired = mEventManager.waitForClickChange(key, origKeyKeyEvent);
                        } else {
                            timeoutExpired = true;
                        }

                        synchronized (mQueueLock) {

                            //Timer released or other combo key
                            Boolean isDefault = mEventManager.getInvokedDefault() || !origKeyKeyEvent.equals(key.getKeyEvent());
                            if (timeoutExpired && mEventManager.hasState(State.ONGOING) || isDefault) {
                                String eventAction = mEventManager.getAction(ActionType.CLICK);
                                isDefault = isDefault || eventAction == null;
                                if (Common.debug())
                                    Log.d(tag, "Invoking click action: " + (isDefault ? "<default>" : eventAction));

                                mEventManager.handleFeedbackAndScreen(eventAction, ActionType.CLICK, mEventManager.getTapCount(), mEventManager.isScreenOn(), mEventManager.getEventTime(), 0);

                                if (isDefault) {
                                    //Dispatch the original key. We cannot dispatch the original event,
                                    // as we cannot insert the down key prior to the current up.
                                    //(it is an option to change this to ACTION_DOWN, set as released, then release)
                                    key.invokeAndRelease(keyEvent);
                                    if (origKeyKeyEvent.equals(key.getKeyEvent())) {
                                        mEventManager.setInvokedDefault();
                                        mEventManager.setState(State.INVOKED);
                                    }
                                } else {
                                    mEventManager.handleKeyAction(eventAction, ActionType.CLICK, mEventManager.isCallButton(), 0, mEventManager);
                                    //mEventManager.setAllKeysReleased(EventKeyType.DEVICE);
                                    mEventManager.setState(State.INVOKED);
                                }
                            } else {
                                if (Common.debug())
                                    Log.d(tag, "No action" + " " + timeoutExpired + " " + mEventManager.mState + " " + mEventManager.getInvokedDefault() + " " + origKeyKeyEvent.equals(key.getKeyEvent()));
                            }
                        }
                    }

                } else if (!down) {
                    if (key.isPressed()) {
                        if (Common.debug())
                            Log.d(tag, "Non-ongoing key is pressed unexpectedly(" + mEventManager.mState.name() + ")");
                    }
                } else {
                    if (Common.debug())
                        Log.d(tag, "Strange: on-ongoing down event (" + mEventManager.mState.name() + ") " + mEventManager.isDownEvent());
                }

				if(Common.debug()) Log.d(tag, "Disabling default dispatching (" + mEventManager.mState.name() + ")");
				
				param.setResult(ORIGINAL.DISPATCHING_REJECT);
				
			} else if (Common.debug()) {
				Log.d(tag, "This key is not handled by the module");
			}
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
