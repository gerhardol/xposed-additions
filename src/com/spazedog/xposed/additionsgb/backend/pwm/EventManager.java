package com.spazedog.xposed.additionsgb.backend.pwm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import android.annotation.SuppressLint;
import android.util.Log;
import android.view.ViewConfiguration;

import com.spazedog.lib.reflecttools.ReflectClass;
import com.spazedog.xposed.additionsgb.Common;
import com.spazedog.xposed.additionsgb.backend.pwm.EventKey.EventKeyType;
import com.spazedog.xposed.additionsgb.backend.pwm.iface.IEventMediator;
import com.spazedog.xposed.additionsgb.backend.service.XServiceManager;
import com.spazedog.xposed.additionsgb.configs.Settings;
import com.spazedog.xposed.additionsgb.tools.MapList;

public final class EventManager extends IEventMediator {
	
	public static enum State { PENDING, ONGOING, INVOKED }
	public static enum LongPressType { NONE, DEFAULT_ACTION, CUSTOM_ACTION }
	
	private final MapList<Integer, EventKey> mEventKeys = new MapList<Integer, EventKey>();
	private final MapList<Integer, EventKey> mInvokedKeys = new MapList<Integer, EventKey>();
	private final List<EventKey> mKeyCache = new ArrayList<EventKey>();
	
	private Integer mLastQueued = 0;
	public State mState = State.PENDING;
	private LongPressType mLongPress = LongPressType.NONE;
	private Integer mTapCount = 0;
	private Long mEventTime = 0L;

	private Boolean mIsScreenOn = true;
	private Boolean mIsExtended = false;
	private Boolean mIsCallButton = false;
	private Integer mTapTimeout = 0;
	private Integer mPressTimeout = 500; //Hardcode default value, used in determing vality of event
	
	protected static final int maxActions = 3 * IEventMediator.ActionType.values().length;
	//actions in the order they appear: press 1, tap 1, press 2, tap 2 etc
	private String[] mKeyActions = new String[maxActions];
    //The index for the "last" action
    private int mMaxActionIndex;

	private final Object mEventLock = new Object();

	protected EventManager(ReflectClass pwm, XServiceManager xServiceManager) {
		super(pwm, xServiceManager);
	}
	
	public EventKey initiateKey(Integer keyCode, Boolean isKeyDown, Integer policyFlags, Integer metaState, Long downTime, EventKeyType keyType) {
		final MapList<Integer, EventKey> keys = getKeyMapList(keyType);
		synchronized(mEventLock) {
			EventKey eventKey = keys.get(keyCode);
			
			if (eventKey == null) {
				eventKey = mKeyCache.size() > 0 ? mKeyCache.remove(0) : new EventKey(this);

				keys.put(keyCode, eventKey);
			}
			
			if (isKeyDown) {
				eventKey.initiateInstance(keyCode, fixPolicyFlags(keyCode, policyFlags), metaState, downTime);
			}
			
			eventKey.updateInstance(isKeyDown, keyType);
			
			return eventKey;
		}
	}

	public void recycleKeys(EventKeyType keyType) {
		final MapList<Integer, EventKey> keys = getKeyMapList(keyType);
		synchronized(mEventLock) {
			for (Integer key : keys.keySet()) {
				mKeyCache.add(keys.get(key));
			}
			
			keys.clear();
		}
	}

	public Boolean registerKey(Integer keyCode, Boolean isKeyDown, Boolean isScreenOn, Integer policyFlags, Integer metaState, Long downTime, Long eventTime) {
		synchronized(mEventLock) {
			if (isKeyDown && (eventTime - mEventTime) > 1000 + getLongLongPressDelay()) { // 1000 + longest handled timeout
				releaseAllKeys();
				recycleKeys(EventKeyType.DEVICE);
				recycleKeys(EventKeyType.INVOKED);
			}
			
			mLastQueued = keyCode;
			mEventTime = eventTime;
			Boolean newEvent = false;
			Boolean newKey = !mEventKeys.containsKey(keyCode);

			initiateKey(keyCode, isKeyDown, policyFlags, metaState, downTime, EventKeyType.DEVICE);
			
			if (isKeyDown) {
				if (mState == State.ONGOING && !newKey) {
					//Increase tapCount on the last key (secondary, until more than 2 combinations are supported)
					//the other key(s) are optional
					if (mEventKeys.size() > 1 && keyCode != mEventKeys.getAt(mEventKeys.size()-1).getCode()) {
						if(Common.debug()) Log.d(TAG, "Repeated tap event");						
					} else {
						if(Common.debug()) Log.d(TAG, "Registering new tap event");
						mTapCount += 1;
					}
					
				//TODO: Original code checked hasState(State.ONGOING, State.INVOKED), reason?
				} else if (mState == State.ONGOING && getKeyCount(EventKeyType.DEVICE) > 1 && isDownEvent()) {
					if(Common.debug()) Log.d(TAG, "Registering new combo event");
					
					mTapCount = 0;
					newEvent = true;
					
				} else {
					if(Common.debug()) Log.d(TAG, "Registering new single event");
					
					if (getKeyCount(EventKeyType.DEVICE) > 1) {
						recycleKeys(EventKeyType.DEVICE);
						initiateKey(keyCode, isKeyDown, policyFlags, metaState, downTime, EventKeyType.DEVICE);
					}
					
					mTapCount = 0;
					newEvent = true;
				}

				if (newEvent) {
					String configName = mEventKeys.joinKeys(":");
					
					if (mEventKeys.size() == 1) {
						configName += ":0";
					}
					
					if(Common.debug()) Log.d(TAG, "Getting actions for the key combo '" + configName + "'");

					mState = State.ONGOING;
					mIsScreenOn = isScreenOn;
					mIsExtended = mXServiceManager.isPackageUnlocked();
					mIsCallButton = mXServiceManager.getBooleanGroup(Settings.REMAP_KEY_ENABLE_CALLBTN, configName);
					mTapTimeout = mXServiceManager.getInt(Settings.REMAP_TIMEOUT_DOUBLECLICK, ViewConfiguration.getDoubleTapTimeout());
					mPressTimeout = mXServiceManager.getInt(Settings.REMAP_TIMEOUT_LONGPRESS, ViewConfiguration.getLongPressTimeout());

					String appCondition = !isScreenOn ? null : isKeyguardShowing() ? "guard" : mIsExtended ? getPackageNameFromStack(0, StackAction.INCLUDE_HOME) : null;
					List<String> actions = null;
					if (appCondition != null && mIsExtended) {
						actions = mXServiceManager.getStringArrayGroup(Settings.REMAP_KEY_LIST_ACTIONS.get(appCondition), configName, null);
					}
					if (actions == null) {
						actions = mXServiceManager.getStringArrayGroup(Settings.REMAP_KEY_LIST_ACTIONS.get(isScreenOn ? "on" : "off"), configName, null);
					}
					if (actions == null) {
						actions = new ArrayList<String>();
					}
					
					/*
					 * TODO: Update the config file to produce the same output as convertOldConfig()
					 */
					mKeyActions = convertOldConfig(actions);

                    mMaxActionIndex = -1;
                    for (int i=0; i < maxActions; i++) {
                        if (!mIsExtended) {
 							/*
							 * Only include Click and Long Press, also excluding Application Launch on non-pro versions
							 */
                            if (mKeyActions[i] != null) {
                                if (getKeyCount(EventKeyType.DEVICE) != 1 || i >= 2 ||
                                        mKeyActions[i].startsWith("launcher") || mKeyActions[i].startsWith("tasker")) {
                                    mKeyActions[i] = null;
                                }
                            }
                        }
                        if (mKeyActions[i] != null) {
                            //The longest to wait for more events
                            mMaxActionIndex = i;
                        }
                    }

                    if (mMaxActionIndex < 1 && (mEventKeys.size() == 1) && mIsExtended) {
                        //Find if there are multi keys that this key need to wait for
                        //This event need to wait at most for keyUp
                        if (mXServiceManager.getBoolean(Settings.CHECK_UNCONFIGURED_PRIMARY_KEY)) {
                            mMaxActionIndex = 1;
                        } else {
                            configName = this.mEventKeys.keyList().get(0) + ":";
                            ArrayList<String> mKeyList = (ArrayList<String>) mXServiceManager.getStringArray(Settings.REMAP_LIST_KEYS, new ArrayList<String>());
                            for (String key: mKeyList) {
                                if(key.startsWith(configName)){
                                    mMaxActionIndex = 1;
                                    break;
                                }
                            }
                        }
                    }
					//If nothing to do, this is not a new event
					if(mMaxActionIndex < 0) {
						mState = State.PENDING;
						newEvent = false;
					}
				} else {
					mIsCallButton = false;
				}
			}

			return newEvent;
		}
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

	public Boolean isHandledKey() {
		return (mMaxActionIndex >= 0);
	}

	public Boolean isDownEvent() {
		Integer count = mEventKeys.size();
		
		for (Integer key : mEventKeys.keySet()) {
			if (!mEventKeys.get(key).isPressed()) {
				return false;
			}
		}
		
		return count > 0;
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
	
	private MapList<Integer, EventKey> getKeyMapList(EventKeyType keyType) {
		final MapList<Integer, EventKey> keys;
		if (keyType == EventKeyType.INVOKED) {
			keys = mInvokedKeys;
		} else {
			keys = mEventKeys;			
		}
		return keys;
	}

	public Integer getKeyCount(EventKeyType keyType) {
		final MapList<Integer, EventKey> keys = getKeyMapList(keyType);
		return keys.size();
	}
	
	public EventKey getKey(Integer keyCode, EventKeyType keyType) {
		final MapList<Integer, EventKey> keys = getKeyMapList(keyType);
		return keys.get(keyCode);
	}
	
	public Collection<EventKey> getKeys(EventKeyType keyType) {
		final MapList<Integer, EventKey> keys = getKeyMapList(keyType);
		return keys.values();
	}
	
	public Boolean isCallButton() {
		return mIsCallButton;
	}
	
	//public Boolean isExtended() {
	//	return mIsExtended;
	//}
	
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
	
	public State setState(State state) {
		State oldState = mState;
		mState = state;
		
		return oldState;
	}
	
	public Boolean hasState(State... states) {
		for (int i=0; i < states.length; i++) {
			if (mState == states[i]) {
				return true;
			}
		}
		
		return false;
	}

	public LongPressType getLongPress() {
		return mLongPress;
	}

	public void setLongPress(LongPressType longPress) {
		mLongPress = longPress;
	}

	public Integer getLastQueuedKeyCode() {
		return mLastQueued;
	}
	
	public void releaseAllKeys() {
		for (EventKey key: mEventKeys.values()) {
			key.release();
		}
		for (EventKey key: mInvokedKeys.values()) {
			key.release();
		}
	}
	
	public Boolean waitForChange(Integer timeout) {
		Long lastEventTime = mEventTime;
		
		do {
			try {
				Thread.sleep(1);
				
			} catch (Throwable e) {}
			
			timeout -= 1;
			
		} while (lastEventTime.equals(mEventTime) && timeout > 0);
		
		return timeout <= 0;
	}
}
