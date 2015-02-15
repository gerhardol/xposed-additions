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
import com.spazedog.xposed.additionsgb.backend.pwm.EventManager.State;
import com.spazedog.xposed.additionsgb.backend.pwm.iface.IEventMediator.ActionType;
import com.spazedog.xposed.additionsgb.backend.pwm.iface.IMediatorSetup.ORIGINAL;
import com.spazedog.xposed.additionsgb.backend.pwm.iface.IMediatorSetup.SDK;
import com.spazedog.xposed.additionsgb.backend.service.XServiceManager;
import com.spazedog.xposed.additionsgb.backend.service.XServiceManager.XServiceBroadcastListener;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XCallback;

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
	public static void handleLoadPackage() {
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
			if (mEventManager.hasState(State.REPEATING)) {
				if (mEventManager.getInvokedDefault()) {
					//The timeout has already occurred when default is dispatched
					param.setResult(10);
				}
				else {
					//The timeout is longer than usual, handled after the first injected key
					param.setResult(10 + mEventManager.getLongLongPressDelay());
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
	protected final XC_MethodHook hook_interceptKeyBeforeQueueing = new XC_MethodHook(XCallback.PRIORITY_DEFAULT + 100) {
		@Override
		protected final void beforeHookedMethod(final MethodHookParam param) {
			final Integer methodVersion = SDK.METHOD_INTERCEPT_VERSION;
            final KeyEvent keyEvent;
            final Object keyObject;
            final Integer POLICYFLAGS_POS;
            final Integer ISSCREENON_POS;
            if (methodVersion > 1) {
                POLICYFLAGS_POS = 1;
                ISSCREENON_POS = 2;
                final Integer KEYEVENT_POS = 0;
                keyEvent = (KeyEvent) param.args[KEYEVENT_POS];
                keyObject = keyEvent;
            } else {
                POLICYFLAGS_POS = 5;
                ISSCREENON_POS = 6;
                Integer keyCode = (Integer)param.args[3];
                Integer action = (Integer)param.args[1];
                Long eventTime = android.os.SystemClock.uptimeMillis();
                Long downTime = ((Long) param.args[0]) / 1000 / 1000;
                Integer repeatCount = 0;
                Integer metaState = 0;
                keyEvent = new KeyEvent(downTime,eventTime,action,keyCode,repeatCount,metaState);
                keyObject = keyCode;
            }
            Integer policyFlags = (Integer) (param.args[POLICYFLAGS_POS]);
            Boolean isScreenOn = (Boolean) (param.args[ISSCREENON_POS]);

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
						(keyEvent.getFlags() & ORIGINAL.FLAG_INJECTED) != 0 : (policyFlags & ORIGINAL.FLAG_INJECTED) != 0;
				
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
						//param.setResult(ORIGINAL.QUEUEING_ALLOW);
                        //noinspection UnnecessaryReturnStatement
                        return;

					} else if ((policyFlags & ORIGINAL.FLAG_INJECTED) != 0) {
						/*
						 * Some ROM's disables features on injected keys. So let's remove the flag.
						 */
						param.args[POLICYFLAGS_POS] = policyFlags & ~ORIGINAL.FLAG_INJECTED;
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
							mEventManager.pokeUserActivity(mEventManager.getEventChangeTime(), false);
						}
						
					} else {
						if (mEventManager.hasState(State.PENDING) || !mEventManager.isHandledKey(keyCode)) {
							if(Common.debug()) Log.d(tag, "Unconfigured key, no action");
							return;
						}
						if(Common.debug()) Log.d(tag, "Continuing ongoing event");
					}
					
					if (down) {
						mEventManager.performHapticFeedback(keyEvent, HapticFeedbackConstants.VIRTUAL_KEY, policyFlags);
					}
					
					if(Common.debug()) Log.d(tag, "Passing the event to the queue (" + mEventManager.stateName() + ")");
					
					//The default handling may "supress" keys we configure, so avoid default handling in some situations
					if (!mEventManager.hasState(State.PENDING)) {
                        param.setResult(ORIGINAL.QUEUEING_ALLOW);
                    }
                    //noinspection UnnecessaryReturnStatement
                    return;
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
	protected XC_MethodHook hook_interceptKeyBeforeDispatching = new XC_MethodHook(XCallback.PRIORITY_DEFAULT + 100) {
		@TargetApi(Build.VERSION_CODES.HONEYCOMB_MR1)
		@Override
		protected final void beforeHookedMethod(final MethodHookParam param) {
            mActiveDispatching = true;

            final Integer methodVersion = SDK.METHOD_INTERCEPT_VERSION;
            final KeyEvent keyEvent;
            final Integer POLICYFLAGS_POS;
            if (methodVersion > 1) {
                POLICYFLAGS_POS = 2;
                final Integer KEYEVENT_POS = 1;
                keyEvent = (KeyEvent) param.args[KEYEVENT_POS];
            } else {
                POLICYFLAGS_POS = 7;
                Integer keyCode = (Integer) param.args[3];
                Integer action = (Integer) param.args[1];
                Long eventTime = android.os.SystemClock.uptimeMillis();
                Long downTime = eventTime;
                Integer repeatCount = (Integer) param.args[6];
                Integer metaState = 0;
                keyEvent = new KeyEvent(downTime, eventTime, action, keyCode, repeatCount, metaState);
            }
            Integer policyFlags = (Integer) (param.args[POLICYFLAGS_POS]);

            Integer keyCode = keyEvent.getKeyCode();
            Integer action = keyEvent.getAction();
            Integer repeatCount = keyEvent.getRepeatCount();
            Boolean down = action == KeyEvent.ACTION_DOWN;

            EventKey key = mEventManager.getKey(keyCode);
            String tag = TAG + "#Dispatching/" + (down ? "Down " : "Up ") + keyCode + "(" + mEventManager.getTapCount() + "," + repeatCount + "): ";

            if (key == null || mEventManager.hasState(State.PENDING)) {
                if (Common.debug()) Log.d(tag, "Unconfigured key, not handling");
                //Return, do default handling, not call:  param.setResult(ORIGINAL.DISPATCHING_ALLOW);
                return;
            }

        	/*
			 * Using KitKat work-around from the InputManager Hook
			 */
            Boolean isInjected = SDK.MANAGER_HARDWAREINPUT_VERSION > 1 ?
                    (keyEvent.getFlags() & ORIGINAL.FLAG_INJECTED) != 0 : (policyFlags & ORIGINAL.FLAG_INJECTED) != 0;

            if (mEventManager.hasState(State.INVOKED)) {
                if (isInjected) {
                    //All in this state should be explicitly injected

                    if ((policyFlags & ORIGINAL.FLAG_INJECTED) != 0) {
                        //Restore original flags
                        param.args[POLICYFLAGS_POS] = policyFlags & ~ORIGINAL.FLAG_INJECTED;
                    }
                    if (Common.debug())
                        Log.d(tag, "Dispatching INVOKED injected key");
                    //param.setResult(ORIGINAL.DISPATCHING_ALLOW);
                    //noinspection UnnecessaryReturnStatement
                    return;

                } else {
                    if (Common.debug())
                        Log.d(tag, "Already handled device key (" + mEventManager.stateName() + ") " + mEventManager.isDownEvent());
                    param.setResult(ORIGINAL.DISPATCHING_REJECT);
                    //noinspection UnnecessaryReturnStatement
                    return;
                }

            } else if (mEventManager.hasState(State.REPEATING)) {
				/*
				 * When we disallow applications from getting the event, we also disable repeats. 
				 * This is a hack where we create a controlled injection loop to simulate repeats. 
				 * 
				 * If we did not have to support GB, then we could have just returned the timeout to force repeat without global dispatching. 
				 * But since we have GB to think about, this is the best solution. 
				 */
                Boolean dispatch = true;
                //Any down key is repeating
                if (down) {
                    if (Common.debug()) Log.d(tag, "Injecting a new repeat " + repeatCount);

                    Long origEventContext = mEventManager.getEventStartTime();

                    Boolean timeoutExpired;
                    Boolean alwaysDispatch = false;
                    Boolean longLongInvokedFirstRepeat = false;
                    if (repeatCount == 0 ||
                            ((repeatCount == 1) && (mEventManager.getInvokedDefault()))) {
                        //No delay dispatching this repeat, wait already done in ONGOING the timeout
                        //Waiting is done before dispatching the next
                        timeoutExpired = true;
                        alwaysDispatch = true;
                    } else {
                        longLongInvokedFirstRepeat = (repeatCount == 1);
                        Integer curTimeout = longLongInvokedFirstRepeat ?  mEventManager.getLongLongPressDelay() :
                                SDK.VIEW_CONFIGURATION_VERSION > 1 ? ViewConfiguration.getKeyRepeatDelay() : 50;
                        timeoutExpired = mEventManager.waitForLongpressChange(curTimeout, origEventContext);
                    }

                    synchronized (mQueueLock) {
                        //Check state
                        if (timeoutExpired && mEventManager.hasState(State.REPEATING) &&
                                origEventContext.equals(mEventManager.getEventStartTime())) {
                            //State is good to insert a new repeat
                            mEventManager.injectInputEvent(keyEvent, KeyEvent.ACTION_DOWN, keyEvent.getRepeatCount() + 1, policyFlags);
                        } else {
                            //The first repeat must be dispatched, not waiting for timeout
                            if (!alwaysDispatch) {
                                dispatch = false;
                                //Release invoked keys when next up is dispatched (could be inserted here, but the other situation must be handled anyway)
                            }
                        }
                    }
                    if (dispatch && longLongInvokedFirstRepeat) {
                        //The inserted key long press occurred, give normal longpress feedback
                        mEventManager.performHapticFeedback(keyEvent, HapticFeedbackConstants.LONG_PRESS, policyFlags);
                    }
                }

                if (!down) {
                    synchronized (mQueueLock) {
                        //Release invoked long press keys on any up key
                        if (mEventManager.getLongPressKeyCode() > 0) {
                            if(!isInjected) {
                                if (Common.debug())
                                    Log.d(tag, "Key up, ending invoked longpress from device key");
                                dispatch = false;
                            } else {
                                if (Common.debug())
                                    Log.d(tag, "Strange: Key up, ending longpress from injected key");
                            }
                            //Invoke key up
                            mEventManager.setState(State.INVOKED);
                            mEventManager.setLongPressKeyCode(-1);
                        } else {
                            if (Common.debug())
                                Log.d(tag, "Key up, ending default longpress from device key up");
                            //Default invoked keys. Primary should not be sent, if the handling is changed just release
                            mEventManager.setState(State.PENDING);
                        }
                    }
                }

                if (dispatch) {
                    if ((policyFlags & ORIGINAL.FLAG_INJECTED) != 0) {
                        //Restore original flags
                        param.args[POLICYFLAGS_POS] = policyFlags & ~ORIGINAL.FLAG_INJECTED;
                    }
                    //param.setResult(ORIGINAL.DISPATCHING_ALLOW);
                    //noinspection UnnecessaryReturnStatement
                    return;
                } else {
                    param.setResult(ORIGINAL.DISPATCHING_REJECT);
                }

            } else if (mEventManager.hasState(State.ONGOING)) {

                if (down) {
                    if (Common.debug()) Log.d(tag, "Waiting on long press timeout");
                    Long origEventContext = mEventManager.getEventStartTime();
                    Boolean timeoutExpired = mEventManager.waitForPressChange(key, origEventContext);

                    synchronized (mQueueLock) {
                        //Continue normally if state is still OnGoing and timer released
                        //If an event is aborted, only do default handling for secondary
                        //There is no default handling for the primary in a combo
                        //(insert keys with new event will interfere with states)
                        Boolean aborted = (!origEventContext.equals(mEventManager.getEventStartTime()) || mEventManager.hasState(State.PENDING));

                        if (timeoutExpired && mEventManager.hasState(State.ONGOING) || aborted) {
                            String eventAction = mEventManager.getAction(ActionType.PRESS);
                            Boolean isDefault = aborted || eventAction == null;
                            if (Common.debug())
                                Log.d(tag, "Invoking press action: " + (isDefault ? "<default>" : eventAction));

                            if (isDefault) {
      							    /*
									 * The first one MUST be dispatched throughout the system.
									 * Applications can ONLY start tracking from the original event object.
									 */
                                if (!aborted) {
                                    mEventManager.setState(State.REPEATING);
                                }
                                //param.setResult(ORIGINAL.DISPATCHING_ALLOW);
                                return;

                            } else {
                                Integer invokeKeyCode = mEventManager.getActionKeyCode(eventAction);
                                if (invokeKeyCode > 0) {
                                    Integer flags = mEventManager.fixPolicyFlags(invokeKeyCode, 0);
                                    mEventManager.invokeKey(invokeKeyCode, KeyEvent.ACTION_DOWN, flags);
                                    mEventManager.performLongPressFeedback();
                                    mEventManager.setState(State.REPEATING);
                                    mEventManager.setLongPressKeyCode(invokeKeyCode);
                                } else {
                                    mEventManager.handleEventAction(eventAction);
                                    mEventManager.setState(State.INVOKED);
                                }
                            }
                        } else {
                            if (Common.debug())
                                Log.d(tag, "No action" + " timeout:" + timeoutExpired + " aborted:" + aborted + " " + mEventManager.stateName());
                        }
                    }

                } else {
                    //ONGOING key down
                    Boolean timeoutExpired;
                    Long origEventContext = mEventManager.getEventStartTime();
                    if (mEventManager.hasMoreActions()) {
                        if (Common.debug()) Log.d(tag, "Waiting on tap timeout");

                        timeoutExpired = mEventManager.waitForClickChange(key, origEventContext);
                    } else {
                        timeoutExpired = true;
                    }

                    synchronized (mQueueLock) {

                        //Abort, primary etc: See long press
                        Boolean aborted = (!origEventContext.equals(mEventManager.getEventStartTime()) || mEventManager.hasState(State.PENDING));
                        if (timeoutExpired && mEventManager.hasState(State.ONGOING) || aborted) {
                            String eventAction = mEventManager.getAction(ActionType.CLICK);
                            Integer invokeKeyCode = -1;
                            Boolean isDefault = aborted;
                            if (!isDefault && mEventManager.isCallButton()) {
                                //Call button overrides configuration of the button (unless the sequence was aborted)
                                invokeKeyCode = mEventManager.invokeCallButton();
                            } else {
                                isDefault = isDefault || eventAction == null;
                            }

                            if (Common.debug()) {
                                String str;
                                if (invokeKeyCode > 0) {
                                    str = "Callcode:"+invokeKeyCode;
                                } else {
                                    str = (isDefault ? "<default>" : eventAction);
                                }
                                Log.d(tag, "Invoking click action: " + str);
                            }

                            if (isDefault) {
                                if (!aborted) {
                                    mEventManager.setState(State.INVOKED);
                                }

                                mEventManager.injectInputEvent(keyEvent, KeyEvent.ACTION_MULTIPLE, 0, policyFlags);

                            } else {
                                if (invokeKeyCode <= 0) {
                                    invokeKeyCode = mEventManager.getActionKeyCode(eventAction);
                                }
                                final Integer flags = mEventManager.fixPolicyFlags(invokeKeyCode,0);
                                //Wake up if needed, let Android drop key if WAKE_DROPPED
                                //Explicitly waking up seem to cause problems waking for some devices, if added should be for non-key only
                                //mEventManager.handleScreen(ActionType.CLICK, mEventManager.isScreenOn(), mEventManager.getEventChangeTime(), flags);
                                //Probably better to require the user to insert the wakeup with app shortcut

                                if (invokeKeyCode > 0) {
                                    mEventManager.invokeKey(invokeKeyCode, KeyEvent.ACTION_MULTIPLE, flags);
                                } else {
                                    mEventManager.handleEventAction(eventAction);
                                }
                                mEventManager.setState(State.INVOKED);
                            }
                        } else {
                            if (Common.debug())
                                Log.d(tag, "No action" + " timeout:" + timeoutExpired + " aborted:" + aborted + " " + mEventManager.stateName());
                        }
                    }
                }

                if (Common.debug())
                    Log.d(tag, "Disabling default dispatching (" + mEventManager.stateName() + ")");

                param.setResult(ORIGINAL.DISPATCHING_REJECT);

                //end ongoing
            } else if (Common.debug()) {
				Log.w(tag, "Strange: This key/state is not handled by the module(" + mEventManager.stateName() + ") " + mEventManager.isDownEvent());
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
