package com.spazedog.xposed.additionsgb.backend.pwm;

import java.util.ArrayList;
import java.util.List;

import android.annotation.SuppressLint;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

import com.spazedog.lib.reflecttools.ReflectClass;
import com.spazedog.xposed.additionsgb.Common;
import com.spazedog.xposed.additionsgb.backend.pwm.EventKey.EventKeyType;
import com.spazedog.xposed.additionsgb.backend.pwm.iface.IEventMediator;
import com.spazedog.xposed.additionsgb.backend.service.XServiceManager;
import com.spazedog.xposed.additionsgb.configs.Settings;

public final class EventManager extends IEventMediator {
	
	public static enum State { PENDING, ONGOING, REPEATING, INVOKED }

    private final int cPrimary = 0;
    private final int cSecondary = 1;
	private final EventKey[] mDeviceKeys = new EventKey[2];
	private final EventKey[] mInvokedKeys = new EventKey[1];

	public State mState = State.PENDING;
	private Integer mTapCount = 0;
	private Long mEventTime = 0L; //time for last event change
    //ongoing combo timeouts
    private Boolean mComboStarted = false;
    private Boolean mComboPendingEnd = false;

    private Boolean mInvokedIsDefault = false;

	private Boolean mIsScreenOn = true;
	private Boolean mIsExtended = false;
	private Boolean mIsCallButton = false;
	private Integer mTapTimeout = 0;
	private Integer mPressTimeout = 500; //Hardcoded default value, used in determining validity of event
	
	protected static final int maxActions = 3 * IEventMediator.ActionType.values().length;
	//actions in the order they appear: press 1, tap 1, press 2, tap 2 etc
	private String[] mKeyActions = new String[maxActions];
    //The index for the "last" action
    private int mMaxActionIndex;

	private final Object mEventLock = new Object();

	protected EventManager(ReflectClass pwm, XServiceManager xServiceManager) {
		super(pwm, xServiceManager);
        for (int i = 0; i < mDeviceKeys.length; i++) {
            mDeviceKeys[i] = new EventKey(this);
        }
        for (int i = 0; i < mInvokedKeys.length; i++) {
            mInvokedKeys[i] = new EventKey(this);
        }
	}
	
	public EventKey initiateKey(KeyEvent keyEvent, Integer policyFlags, EventKeyType keyType) {
		synchronized(mEventLock) {
            final EventKey[] keys = getKeyList(keyType);
            for (EventKey key: keys) {
                //should not happen, but reuse if so
                if (key.getCode().equals(keyEvent.getKeyCode())) {
                    if(Common.debug()) Log.d(TAG, "Strange: keyCode already in array");
                    key.setUnused();
                    break;
                }
            }
            EventKey eventKey = null;
            for (EventKey key: keys) {
                if (!key.isUsed()) {
                    key.initiateInstance(keyEvent, policyFlags);
                    eventKey = key;
                    break;
                }
            }

			return eventKey;
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
                            keyActions[i].startsWith("launcher") || keyActions[i].startsWith("tasker")) {
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
                if (key.startsWith(configName)) {
                    maxActionIndex = 1;
                    break;
                }
            }
        }

        //The previous event is cancelled
        if (maxActionIndex < 0) {
            mState = State.PENDING;
            mIsCallButton = false;

        } else {
            mState = State.ONGOING;
            mIsScreenOn = isScreenOn;
            mIsCallButton = mXServiceManager.getBooleanGroup(Settings.REMAP_KEY_ENABLE_CALLBTN, configName);
            mKeyActions = keyActions;
        }
        mTapCount = 0;
        mInvokedIsDefault = false;
        mComboStarted = false;
        mComboPendingEnd = false;

        return maxActionIndex;
    }

	public Boolean registerKey(KeyEvent keyEvent, Boolean isScreenOn, Integer policyFlags) {
        Boolean newEvent = false;
		synchronized(mEventLock) {
            Integer keyCode = keyEvent.getKeyCode();
            Boolean isKeyDown = keyEvent.getAction() == KeyEvent.ACTION_DOWN;
            // Make sure not event hangs: Cancel at new presses 1000 + longest handled timeout
			if (isKeyDown && (keyEvent.getEventTime() - mEventTime) > 1000 + getLongLongPressDelay()) {
				releaseAllKeys();
			}

            EventKey key = getKey(keyCode, EventKeyType.DEVICE);
            Boolean keyExists = key != null;
            //Set event time, abort timeouts
            mEventTime = keyEvent.getEventTime();

            if (isKeyDown) {
				if (mState == State.ONGOING && keyExists) {
                    //Part of an ongoing event
                    //Combo is aborted if primary is up, so this is a repeat
                    if (mDeviceKeys[cSecondary].isUsed() && mDeviceKeys[cPrimary].getCode().equals(keyCode)) {
                        if (Common.debug()) Log.i(TAG, "Ignoring unexpected primary repeat");
                        return false;
                    }
                    if (Common.debug()) Log.d(TAG, "Registering new tap event");
                    mTapCount += 1;

                } else {
                    //Pull, in case they are changed
                    mTapTimeout = mXServiceManager.getInt(Settings.REMAP_TIMEOUT_DOUBLECLICK, ViewConfiguration.getDoubleTapTimeout());
                    mPressTimeout = mXServiceManager.getInt(Settings.REMAP_TIMEOUT_LONGPRESS, ViewConfiguration.getLongPressTimeout());
                    mIsExtended = mXServiceManager.isPackageUnlocked();

                    mMaxActionIndex = -1;
                    if (!keyExists) {
                        if (mDeviceKeys[cPrimary].isUsed() && mDeviceKeys[cSecondary].isUsed()) {
                            if(Common.debug()) Log.i(TAG, "Exceeding combo, resetting");
                            releaseAllKeys();

                        } else if (mState == State.ONGOING && mDeviceKeys[cPrimary].isUsed() && isDownEvent() && mIsExtended) {
                            if (Common.debug()) Log.d(TAG, "Registering new combo event");

                            mMaxActionIndex = getActionsForEvent(mDeviceKeys[cPrimary].getCode(), keyCode, isScreenOn);
                            if (mMaxActionIndex > 0) {
                                //Ignore the primary, until invoked/default
                                mComboStarted = true;
                            }
                        } else {
                            //Intentionally empty: No ongoing, handled next
                        }
                    }
                    if(mMaxActionIndex < 0){
                        if(Common.debug()) Log.d(TAG, "Registering new single event");

                        //This is not continuing, make sure existing are removed (default handled)
                        releaseAllKeys();
                        mMaxActionIndex = getActionsForEvent(keyCode, 0, isScreenOn);
                    }
                    if(mMaxActionIndex >= 0) {
                        key = initiateKey(keyEvent, policyFlags, EventKeyType.DEVICE);
                        newEvent = true;
                    }
                }

			} else {
                if (mState == State.ONGOING && mDeviceKeys[cSecondary].isUsed() && mDeviceKeys[cPrimary].getCode().equals(keyCode)) {
                    //Primary key in an existing combo
                    //If secondary is up execute action
                    //if secondary is down, set default handling
                    if(mDeviceKeys[cSecondary].isPressed()) {
                        if (Common.debug()) Log.d(TAG, "Primary up, default handling");
                        mComboPendingEnd = true;
                    }
                }
            }
            if (key != null) {
                key.setKetPressDevice(isKeyDown);
            }
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
		for (EventKey key : mDeviceKeys) {
			if (!key.isPressed() && key.isUsed()) {
				return false;
			}
		}
		
		return true;
	}
	
	public Integer getLongLongPressDelay() {
		return 2 * mPressTimeout;
	}

	public Integer getTapCount() {
		return mTapCount;
	}
	
	public Long getEventTime() {
		return mEventTime;
	}

    public Boolean getInvokedDefault() { return mInvokedIsDefault; }

    public void setInvokedDefault() { mInvokedIsDefault = true; }

 	public Boolean isCallButton() {
		return mIsCallButton;
	}

	public Boolean isScreenOn() {
		return mIsScreenOn;
	}
	
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
	
	public void setState(State state) {
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

    public EventKey[] getKeyList(EventKeyType keyType) {
        final EventKey[] keys;
        if (keyType == EventKeyType.INVOKED) {
            keys = mInvokedKeys;
        } else {
            keys = mDeviceKeys;
        }
        return keys;
    }

    public Boolean isHandledKey(int keyCode, EventKeyType keyType) {
        return getKey(keyCode, keyType) != null;
    }

    public Boolean isHandledKey(int keyCode) {
        return getKey(keyCode) != null;
    }

    public EventKey getKey(Integer keyCode, EventKeyType keyType) {
        for(EventKey key: getKeyList(keyType)) {
            if (key != null && key.getCode().equals(keyCode) && key.isUsed()) {
                return key;
            }
        }
        return null;
    }

    public EventKey getKey(Integer keyCode) {
        //Check invoked first, to handle mapping to same
        EventKey key = getKey(keyCode, EventKeyType.INVOKED);
        if (key == null) {
            key = getKey(keyCode, EventKeyType.DEVICE);
        }
        return key;
    }

    private void recycleKeys(EventKeyType keyType) {
        synchronized(mEventLock) {
            final EventKey[] keys = getKeyList(keyType);
            for (EventKey key : keys) {
                key.setUnused();
            }
        }
    }

    private void releaseAllKeys() {
        for (EventKey key: mDeviceKeys) {
            key.release(null);
        }
        recycleKeys(EventKeyType.DEVICE);
        for (EventKey key: mInvokedKeys) {
            key.release(null);
        }
        recycleKeys(EventKeyType.INVOKED);
    }

    public void setAllKeysReleased(EventKeyType keyType) {
        for (EventKey key: getKeyList(keyType)) {
            key.setKeyPressSent(false);
        }
    }

    //wait for timeout or state change, but let primary timeout first
    public Boolean waitForPressChange(EventKey key, KeyEvent origContext) {
        Integer timeout = this.getPressTimeout();
        Long lastEventTime = mEventTime; //Context for current event status
        Boolean comboStarted = false;
        int allowedFailures = 3;

        do {
            try {
                Thread.sleep(1);

            } catch (Throwable e) {}

            timeout -= 1;

            if (mComboStarted) {
                synchronized(mEventLock) {
                    //Extend time for primary when secondary starts
                    if (mComboStarted && key == mDeviceKeys[cPrimary]) {
                        mComboStarted = false;
                        comboStarted = true;
                    }
                }
            }
        } while ((mState == State.ONGOING &&
                (timeout > 0 && lastEventTime.equals(mEventTime) || comboStarted) ||allowedFailures-->0) &&
                origContext.equals(key.getKeyEvent()));
        //if (Common.debug())
        //    Log.d(TAG, "waitForPressChange: " + key.getCode()+" " + timeout + " " + mState + " " + origContext.equals(key.getKeyEvent())+lastEventTime.equals(mEventTime)+ comboStarted);

        return timeout <= 0 && !comboStarted;
    }

    //wait for timeout or state change
    public Boolean waitForLongpressChange(Integer timeout) {

        do {
            try {
                Thread.sleep(1);

            } catch (Throwable e) {}

            timeout -= 1;
            //Only abort for state change
        } while (timeout > 0 && mState == State.REPEATING);

        return timeout <= 0;
    }

    public Boolean waitForClickChange(EventKey key, KeyEvent origContext) {
        Integer timeout = getTapTimeout();
        Long lastEventTime = mEventTime;
        Boolean comboPendingEnd = false;

        do {
            try {
                Thread.sleep(1);

            } catch (Throwable e) {}

            timeout -= 1;

            if (mComboPendingEnd) {
                synchronized(mEventLock) {
                    //Extend time for primary when secondary starts
                    if (mComboPendingEnd && key == mDeviceKeys[cPrimary]) {
                        mComboPendingEnd = false;
                        comboPendingEnd = true;
                    }
                }
            }
        } while ((timeout > 0 && mState == State.ONGOING && lastEventTime.equals(mEventTime) && !comboPendingEnd ||
                //wait until (primary) key is released
                key.isPressed() && getInvokedDefault()) &&
                origContext.equals(key.getKeyEvent()));
        //if (Common.debug())
        //    Log.d(TAG, "waitForClickChange: " + key.getCode()+" " + timeout + " " + mState + " " + origContext.equals(key.getKeyEvent())+lastEventTime.equals(mEventTime)+ comboPendingEnd);

        return timeout <= 0 && !comboPendingEnd;
    }
}
