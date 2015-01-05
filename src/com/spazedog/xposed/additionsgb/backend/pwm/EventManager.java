package com.spazedog.xposed.additionsgb.backend.pwm;

import java.util.ArrayList;
import java.util.List;

import android.annotation.SuppressLint;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

import com.spazedog.lib.reflecttools.ReflectClass;
import com.spazedog.xposed.additionsgb.Common;
import com.spazedog.xposed.additionsgb.backend.pwm.iface.IEventMediator;
import com.spazedog.xposed.additionsgb.backend.service.XServiceManager;
import com.spazedog.xposed.additionsgb.configs.Settings;

public final class EventManager extends IEventMediator {
	
	public static enum State { PENDING, ONGOING, REPEATING, INVOKED }

    private final int EVENTKEY_INVOKED = 0;
    private final int EVENTKEY_PRIMARY = 1;
    private final int EVENTKEY_SECONDARY = 2;

	private final EventKey[] mTrackedKeys = new EventKey[1+EVENTKEY_SECONDARY];

	private State mState = State.PENDING;
    public String stateName(){ return mState.name(); }
	private Integer mTapCount = 0;
    private Long mEventChangeTime = 0L; //time for last event change
    private Long mEventStartTime = 0L; //time for last event start
    //ongoing combo timeouts
    private Boolean mComboStarted = false;
    //The ongoing long press (repeat) key
    private Integer mLongPressKeyCode = -1;

	//private Boolean mIsScreenOn = true;
	private Boolean mIsExtended = false;
	private Boolean mIsCallButton = false;
	private Integer mTapTimeout = 0;
	private Integer mPressTimeout = 500; //Hardcoded default value, used in determining validity of event

    private static final int maxActions = 3 * IEventMediator.ActionType.values().length;
	//actions in the order they appear: press 1, tap 1, press 2, tap 2 etc
	private String[] mKeyActions = new String[maxActions];
    //The index for the "last" action
    private int mMaxActionIndex;

	protected EventManager(ReflectClass pwm, XServiceManager xServiceManager) {
		super(pwm, xServiceManager);
        for (int i = 0; i < mTrackedKeys.length; i++) {
            mTrackedKeys[i] = new EventKey();
        }
	}

    private int getActionsForEvent(int primCode, int secCode, Boolean isScreenOn) {
        String configName = primCode + ":" + secCode;

        if (Common.debug()) Log.d(TAG, "Getting actions for the key combo '" + configName + "'");

        List<String> actions = null;
        String appCondition = null;
        if (isScreenOn) {
            appCondition = isKeyguardShowing() ? "guard" : mIsExtended ? getPackageNameFromStack(0, StackAction.INCLUDE_HOME) : null;
        }
        if (appCondition != null && mIsExtended) {
            actions = mXServiceManager.getStringArrayGroup(Settings.REMAP_KEY_LIST_ACTIONS.get(appCondition), configName, null);
        }
        if (actions == null) {
            actions = mXServiceManager.getStringArrayGroup(Settings.REMAP_KEY_LIST_ACTIONS.get(isScreenOn ? "on" : "off"), configName, null);
        }
        if (actions == null) {
            actions = new ArrayList<String>();
        }

        String[] keyActions = convertOldConfig(actions);

        int maxActionIndex = -1;
        for (int i = 0; i < maxActions; i++) {
            if (!mIsExtended) {
 							/*
							 * Only include Click and Long Press, also excluding Application Launch on non-pro versions
							 */
                //Excluding combo is done when detecting
                if (keyActions[i] != null) {
                    //No triple, double press
                    if (i >= 2 ||
                            //No program, tasker
                            keyActions[i].contains(".") || keyActions[i].startsWith("appshortcut:") || keyActions[i].startsWith("tasker:")) {
                        keyActions[i] = null;
                    }
                }
            }
            if (keyActions[i] != null) {
                //The longest to wait for more events
                maxActionIndex = i;
            }
        }

        if (maxActionIndex < 1 && secCode == 0) {
            //Find if there are multi keys that this key need to wait for
            //This event need to wait at most for keyUp
            configName = primCode + ":";
            ArrayList<String> mKeyList = (ArrayList<String>) mXServiceManager.getStringArray(Settings.REMAP_LIST_KEYS, new ArrayList<String>());
            for (String key : mKeyList) {
                if (key.startsWith(configName) && !key.equals(configName+"0")) {
                    maxActionIndex = 1;
                    break;
                }
            }
        }

        //The previous event is cancelled
        if (maxActionIndex < 0) {
            mState = State.PENDING;
            mIsCallButton = false;
            mComboStarted = false;

        } else {
            mState = State.ONGOING;
            //mIsScreenOn = isScreenOn;
            mIsCallButton = mXServiceManager.getBooleanGroup(Settings.REMAP_KEY_ENABLE_CALLBTN, configName);
            mKeyActions = keyActions;
            if(secCode > 0) {
                mComboStarted = true;
            }
        }
        mTapCount = 0;

        return maxActionIndex;
    }

    public void invokeKey(Integer keyCode, Integer keyAction, Integer flags) {
        abortRepeatingKeys();
        //Add the key to tracked keys
        KeyEvent keyEvent = new KeyEvent(keyAction, keyCode);
        mTrackedKeys[EVENTKEY_INVOKED].initiateInstance(keyEvent, flags);
        injectInputEvent(keyEvent, keyAction, 0, flags);
    }

	public Boolean registerKey(KeyEvent keyEvent, Boolean isScreenOn, Integer policyFlags) {
        Boolean newEvent = false;
        Integer keyCode = keyEvent.getKeyCode();
        Boolean isKeyDown = keyEvent.getAction() == KeyEvent.ACTION_DOWN;
        // Make sure not event hangs: Cancel at new presses 1000 + longest handled timeout
        if (isKeyDown && (keyEvent.getEventTime() - mEventChangeTime) > 1000 + getLongLongPressDelay()) {
            if (hasState(State.ONGOING, State.REPEATING)) {
                if (Common.debug())
                    Log.i(TAG, "Aborting old event: " + (keyEvent.getEventTime() - mEventChangeTime));
            }
            abortRepeatingKeys();
        }

        EventKey key = getDeviceKey(keyCode);
        Boolean keyExists = key != null;
        //Set event time, abort timeouts
        Long currEventChangeTime = mEventChangeTime;
        mEventChangeTime = keyEvent.getEventTime();

        if (isKeyDown) {
            if (mState == State.ONGOING && keyExists) {
                //Part of an ongoing event
                if (mTrackedKeys[EVENTKEY_SECONDARY].isUsed() && mTrackedKeys[EVENTKEY_PRIMARY].getCode().equals(keyCode)) {
                    if (Common.debug()) Log.i(TAG, "Ignoring primary repeat");
                    mEventChangeTime = currEventChangeTime;
                } else {
                    if (Common.debug()) Log.d(TAG, "Registering new tap event");
                    mTapCount += 1;
                }

            } else {
                //New key or handled state, must be new event
                //Set new state after checking if this is a new event

                //Pull, in case they are changed
                //Must not be in REPEAT>ING when doing this
                mTapTimeout = mXServiceManager.getInt(Settings.REMAP_TIMEOUT_DOUBLECLICK, ViewConfiguration.getDoubleTapTimeout());
                mPressTimeout = mXServiceManager.getInt(Settings.REMAP_TIMEOUT_LONGPRESS, ViewConfiguration.getLongPressTimeout());
                mIsExtended = mXServiceManager.isPackageUnlocked();

                mMaxActionIndex = -1;
                if (!keyExists) {
                    if (mTrackedKeys[EVENTKEY_PRIMARY].isUsed() && mTrackedKeys[EVENTKEY_SECONDARY].isUsed()) {
                        if (Common.debug()) Log.i(TAG, "Exceeding combo, resetting");
                        abortRepeatingKeys();

                    } else if (mState == State.ONGOING && mTrackedKeys[EVENTKEY_PRIMARY].isUsed() && isDownEvent() && mIsExtended) {
                        if (Common.debug()) Log.d(TAG, "Registering new combo event");

                        mMaxActionIndex = getActionsForEvent(mTrackedKeys[EVENTKEY_PRIMARY].getCode(), keyCode, isScreenOn);
                        if (mMaxActionIndex >= 0) {
                            mTrackedKeys[EVENTKEY_SECONDARY].initiateInstance(keyEvent, policyFlags);
                        }
                    }
                }
                if (mMaxActionIndex < 0) {
                    if (Common.debug()) Log.d(TAG, "Registering new single event");

                    //This is not continuing, make sure existing are removed (default handled)
                    abortRepeatingKeys();
                    mMaxActionIndex = getActionsForEvent(keyCode, 0, isScreenOn);
                    if (mMaxActionIndex >= 0) {
                        mTrackedKeys[EVENTKEY_PRIMARY].initiateInstance(keyEvent, policyFlags);
                        mTrackedKeys[EVENTKEY_SECONDARY].setUnused();
                        //The context for the start is changed for the first key only
                        mEventStartTime = mEventChangeTime;
                    }
                }
                if (mMaxActionIndex >= 0) {
                    newEvent = true;
                }
            }

        } else {
            //key up
            if (mState == State.ONGOING && mTrackedKeys[EVENTKEY_SECONDARY].isUsed() && mTrackedKeys[EVENTKEY_PRIMARY].getCode().equals(keyCode)) {
                //Primary key in an existing combo, ignore
                if (Common.debug()) Log.d(TAG, "Primary up, default handling");
                mEventChangeTime = currEventChangeTime;
            }
         }
        if (key != null) {
            key.setKetPressDevice(isKeyDown);
        }
        return newEvent;
    }

	@SuppressLint("Assert")
	private String[] convertOldConfig(List<String> oldConfig) {
		/*
		 * This is a tmp method that will be used until
		 * such time where the config file is updated to produce
		 * the same output.
		 * 
		 * TODO: Remove this method
		 */
		
        //Order the actions to the order they occur (by click/repeat):
        //long press before click, single before double
		Integer[] newLocations = new Integer[]{2,0,3,1,5,4};
		assert maxActions == newLocations.length;
		String[] newConfig = new String[newLocations.length];
		
		for (int i=0; i < newLocations.length; i++) {
			Integer x = newLocations[i];
			newConfig[i] = (oldConfig.size() > x ? oldConfig.get(x) : null);
		}
		
		return newConfig;
	}

    //Are all (combo) keys pressed?
	public Boolean isDownEvent() {
        return (!mTrackedKeys[EVENTKEY_PRIMARY].isUsed() ||
                mTrackedKeys[EVENTKEY_PRIMARY].isPressed()) &&
                (!mTrackedKeys[EVENTKEY_SECONDARY].isUsed() ||
                        mTrackedKeys[EVENTKEY_SECONDARY].isPressed());
	}

	public Integer getLongLongPressDelay() {
		return 2 * mPressTimeout;
	}

	public Integer getTapCount() {
		return mTapCount;
	}

    public Long getEventChangeTime() {
        return mEventChangeTime;
    }

    public Long getEventStartTime() {
        return mEventStartTime;
    }

    public Boolean getInvokedDefault() {
        return (getLongPressKeyCode() <= 0);
    }

 	public Boolean isCallButton() {
		return mIsCallButton;
	}

	//public Boolean isScreenOn() { return mIsScreenOn; }
	
	public Integer getPressTimeout() {
		return mPressTimeout;
	}
	
	public Integer getTapTimeout() {
		return mTapTimeout;
	}

	private int getActionIndex(final ActionType atype) {
		int index = mTapCount * 2;
		if (atype == ActionType.CLICK) {index++;}
		return index;
	}

	public String getAction(final ActionType atype) {
		final int index = getActionIndex(atype);
		if (index >= mKeyActions.length) { return null; }
		return mKeyActions[index];
	}
	
	public Boolean hasMoreActions() {
		int index = 1 + getActionIndex(ActionType.CLICK);
        return (index <= this.mMaxActionIndex);
	}

    public Integer getLongPressKeyCode() {
        return mLongPressKeyCode;
    }
    public void setLongPressKeyCode(Integer longPressKeyCode) {
        mLongPressKeyCode = longPressKeyCode;
    }

    public void setState(State state) {
        if(state == mState) {
            return;
        }
        if(mState == State.REPEATING){
            if (state == State.INVOKED) {
                mEventStartTime++;

                //Released invoked keys, device default is handled separately
                EventKey invokedKey = mTrackedKeys[EVENTKEY_INVOKED];
                if (invokedKey.isUsed()) {
                    this.injectInputEvent(invokedKey.getKeyEvent(), KeyEvent.ACTION_UP, 0, invokedKey.getFlags());
                    //invokedKey.setUnused();
                }
            }
        }
		mState = state;
	}
	
	public Boolean hasState(State... states) {
		for (State state: states) {
			if (mState == state) {
				return true;
			}
		}
		
		return false;
	}

    public Boolean isHandledKey(int keyCode) {
        return getKey(keyCode) != null;
    }

    private EventKey getDeviceKey(Integer keyCode) {
        int start = EVENTKEY_PRIMARY;
        int end = EVENTKEY_SECONDARY;

        for(int i = start; i <= end; i++) {
            EventKey key = mTrackedKeys[i];
            if (key.getCode().equals(keyCode) && key.isUsed()) {
                return key;
            }
        }
        return null;
    }

    public EventKey getKey(Integer keyCode) {
        //Check invoked first, to handle mapping to same
        for(EventKey key: mTrackedKeys) {
            if (key != null && key.getCode().equals(keyCode) && key.isUsed()) {
                return key;
            }
        }
        return null;
    }

    private void abortRepeatingKeys() {
        //If ONGOING or REPEAT-default, the default handling fixes the abort (context changed)
        if(mState == State.REPEATING && mTrackedKeys[EVENTKEY_INVOKED].isUsed() &&
                this.getLongPressKeyCode().equals(mTrackedKeys[EVENTKEY_INVOKED].getCode())) {
            this.injectInputEvent(mTrackedKeys[EVENTKEY_INVOKED].getKeyEvent(), KeyEvent.ACTION_UP, 0, mTrackedKeys[EVENTKEY_INVOKED].getFlags());
        }

        mTrackedKeys[EVENTKEY_INVOKED].setUnused();
        mComboStarted = false;
    }

    //wait for timeout or state change, but let primary timeout first
    public Boolean waitForPressChange(EventKey key, Long origContext) {
        Integer timeout = this.getPressTimeout();
        Long lastEventTime = mEventChangeTime; //Context for current event status

        do {
            try {
                Thread.sleep(1);

            } catch (Throwable e) {}

            timeout -= 1;

        } while (mState == State.ONGOING &&
                timeout > 0 && lastEventTime.equals(mEventChangeTime) && (!mComboStarted || key.getCode().equals(mTrackedKeys[EVENTKEY_SECONDARY].getCode())) &&
                origContext.equals(mEventStartTime));
        //if (Common.debug())
        //    Log.d(TAG, "waitForPressChange: " + key.getCode()+" " + timeout + " " + mState + " " + origContext.equals(key.getKeyEvent())+lastEventTime.equals(mEventChangeTime)+ comboStarted);

        return timeout <= 0;
    }

    //wait for timeout or state change
    public Boolean waitForLongpressChange(Integer timeout, Long origContext) {
        Long lastEventTime = mEventChangeTime;

        do {
            try {
                Thread.sleep(1);

            } catch (Throwable e) {}

            timeout -= 1;
            //Only abort for state change
        } while (timeout > 0 && (mState == State.REPEATING)
                && lastEventTime.equals(mEventChangeTime) && origContext.equals(mEventStartTime));

        return timeout <= 0;
    }

    public Boolean waitForClickChange(EventKey key, Long origContext) {
        Integer timeout = getTapTimeout();
        Long lastEventTime = mEventChangeTime;

        do {
            try {
                Thread.sleep(1);

            } catch (Throwable e) {}

            timeout -= 1;

        } while (timeout > 0 && mState == State.ONGOING && lastEventTime.equals(mEventChangeTime) &&
                (!mComboStarted || key.getCode().equals(mTrackedKeys[EVENTKEY_SECONDARY].getCode())) &&
                origContext.equals(mEventStartTime));
        //if (Common.debug())
        //    Log.d(TAG, "waitForClickChange: " +(key == mTrackedKeys[EVENTKEY_SECONDARY])+ key.getCode()+" " + timeout + " " + mState + " " + origContext.equals(mEventStartTime)+lastEventTime.equals(mEventChangeTime)+ mComboStarted);

        return timeout <= 0;
    }
}
