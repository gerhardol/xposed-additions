package com.spazedog.xposed.additionsgb.backend.pwm;

import java.util.ArrayList;
import java.util.List;

import com.spazedog.xposed.additionsgb.backend.service.XServiceManager;
import com.spazedog.xposed.additionsgb.configs.Settings;

import android.os.SystemClock;
import android.view.ViewConfiguration;

public class EventManager {
	public static enum State { PENDING, ONGOING, INVOKED, INVOKED_DEFAULT, CANCELED }
	public static enum Priority { PRIMARY, SECONDARY }

	private final XServiceManager mXServiceManager;

	private final List<Integer> mOnGoingKeyCodes = new ArrayList<Integer>();

	private Integer mTapTimeout = 0;
	private Integer mPressTimeout = 0;

	private final String[] mClickActions = new String[3];
	private final String[] mPressActions = new String[3];

	private Boolean mIsCallButton = false;
	private Boolean mIsExtended = false;

	private State mState = State.PENDING;
	private Boolean mIsDownEvent = false;
	private Boolean mIsCombiEvent = false;
	private Integer mTapCount = 0;
	private Long mEventTime = 0L;
	private Long mDownTime = 0L;

	private Boolean mIsScreenOn = true;
	private String mCurrentApplication;

	private final EventKey mPrimaryKey = new EventKey(Priority.PRIMARY);
	private final EventKey mSecondaryKey = new EventKey(Priority.SECONDARY);

	private final Object mLock = new Object();

	public EventManager(final XServiceManager xserviceManager) {
		mXServiceManager = xserviceManager;
	}

	public void registerEvent(final String currentApplication, final Boolean inKeyguard, final Boolean isScreenOn) {
		synchronized (mLock) {
			mIsExtended = mXServiceManager.isPackageUnlocked();
			mIsCallButton = mXServiceManager.getBooleanGroup(Settings.REMAP_KEY_ENABLE_CALLBTN, (mPrimaryKey.mKeyCode + ":" + mSecondaryKey.mKeyCode));
			mTapTimeout = mXServiceManager.getInt(Settings.REMAP_TIMEOUT_DOUBLECLICK, ViewConfiguration.getDoubleTapTimeout());
			mPressTimeout = mXServiceManager.getInt(Settings.REMAP_TIMEOUT_LONGPRESS, ViewConfiguration.getLongPressTimeout());
			mCurrentApplication = inKeyguard ? "keyguard" : currentApplication;
			mIsScreenOn = isScreenOn;

			/*
			 * This array is to help extract the actions from the preference array.
			 * Since triple actions was not added until later, these was placed at the end to 
			 * keep compatibility with already existing preference files. 
			 * 
			 *  - 0 = Click
			 *  - 1 = Double Click
			 *  - 2 = Long Press
			 *  - 3 = Double Long Press
			 *  - 4 = Triple Click
			 *  - 5 = Triple Long Press
			 */
			final Integer[] oldConfig = new Integer[]{0,1,4,2,3,5};
			final String keyGroupName = mPrimaryKey.mKeyCode + ":" + mSecondaryKey.mKeyCode;
			final String appCondition = !isScreenOn ? null : inKeyguard ? "guard" : mIsExtended ? currentApplication : null;
			List<String> actions = appCondition != null ? mXServiceManager.getStringArrayGroup(Settings.REMAP_KEY_LIST_ACTIONS.get(appCondition), keyGroupName, null) : null;

			if ((mIsCombiEvent && !mIsExtended) || (actions == null && (actions = mXServiceManager.getStringArrayGroup(Settings.REMAP_KEY_LIST_ACTIONS.get(isScreenOn ? "on" : "off"), keyGroupName, null)) == null)) {
				actions = new ArrayList<String>();
			}

			for (int i=0; i < oldConfig.length; i++) {
				final Integer x = oldConfig[i];

				/*
				 * Only include Click and Long Press along with excluding Application Launch on non-pro versions
				 */
				final String action = ((x.equals(0) || x.equals(2)) || mIsExtended) && actions.size() > x && (mIsExtended || !".".equals(actions.get(x))) ? actions.get(x) : null;

				if (i < 3) {
					mClickActions[i] = action;

				} else {
					mPressActions[i-3] = action;
				}
			}
		}
	}

	public Boolean registerKey(final Integer keyCode, final Boolean isKeyDown, final Integer policyFlags) {
		synchronized (mLock) {
			final Long time = SystemClock.uptimeMillis();
			Boolean newEvent = false;

			if (isKeyDown) {
				if ((time - mEventTime) > 1000) {
					mState = State.PENDING;
				}

				if (mState == State.ONGOING && (keyCode.equals(mPrimaryKey.mKeyCode) || keyCode.equals(mSecondaryKey.mKeyCode))) {
					mTapCount += 1;

					if (keyCode.equals(mSecondaryKey.mKeyCode)) {
						mSecondaryKey.mIsKeyDown = true;

					} else {
						mPrimaryKey.mIsKeyDown = true;
					}

				} else if (mState != State.CANCELED && mState != State.PENDING && mPrimaryKey.isKeyDown() && keyCode != mPrimaryKey.mKeyCode && (mSecondaryKey.mKeyCode.equals(0) || keyCode.equals(mSecondaryKey.mKeyCode))) {
					mState = State.ONGOING;
					mTapCount = 0;
					mIsCombiEvent = true;

					mSecondaryKey.mKeyCode = keyCode;
					mSecondaryKey.mPolicyFlags = policyFlags;
					mSecondaryKey.mIsKeyDown = true;

					newEvent = true;

				} else {
					mState = State.ONGOING != mState ? State.ONGOING : State.CANCELED;

					if (State.ONGOING == mState) {
						mTapCount = 0;
						mDownTime = time;
						mIsCombiEvent = false;

						mPrimaryKey.mKeyCode = keyCode;
						mPrimaryKey.mPolicyFlags = policyFlags;
						mPrimaryKey.mIsKeyDown = true;

						mSecondaryKey.mKeyCode = 0;
						mSecondaryKey.mPolicyFlags = 0;
						mSecondaryKey.mIsKeyDown = false;

						newEvent = true;

					} else {
						return false;
					}
				}

				mIsDownEvent = true;
				mIsCallButton = false;

				mPrimaryKey.mRepeatCount = 0;
				mSecondaryKey.mRepeatCount = 0;

			} else {
				if (keyCode.equals(mSecondaryKey.mKeyCode)) {
					mSecondaryKey.mIsKeyDown = false;

				} else {
					mPrimaryKey.mIsKeyDown = false;
				}

				mIsDownEvent = false;
			}

			mEventTime = time;

			mPrimaryKey.mIsLastQueued = mPrimaryKey.mKeyCode.equals(keyCode);
			mSecondaryKey.mIsLastQueued = mSecondaryKey.mKeyCode.equals(keyCode);

			return newEvent;
		} 
	}

	public EventKey getEventKey(final Integer keyCode) {
		return mPrimaryKey.getKeyCode().equals(keyCode) ? mPrimaryKey : 
			mSecondaryKey.getKeyCode().equals(keyCode) ? mSecondaryKey : null;
	}

	public EventKey getEventKey(final Priority priority) {
		switch (priority) {
		case PRIMARY: return mPrimaryKey;
		case SECONDARY: return mSecondaryKey;
		}

		return null;
	}

	public EventKey getParentEventKey(final Integer keyCode) {
		return mPrimaryKey.getKeyCode().equals(keyCode) ? mSecondaryKey : 
			mSecondaryKey.getKeyCode().equals(keyCode) ? mPrimaryKey : null;
	}

	public EventKey getParentEventKey(final Priority priority) {
		switch (priority) {
		case PRIMARY: return mSecondaryKey;
		case SECONDARY: return mPrimaryKey;
		}

		return null;
	}

	public Boolean isCombiEvent() {
		return mIsCombiEvent;
	}

	public Boolean isDownEvent() {
		return mIsDownEvent;
	}

	public Boolean isCallButtonEvent() {
		return mIsCallButton;
	}

	public Boolean isScreenOn() {
		return mIsScreenOn;
	}

	public Boolean hasExtendedFeatures() {
		return mIsExtended;
	}

	public Boolean hasTapActions() {
		return mClickActions[1] != null ||
				mClickActions[2] != null || 
				mPressActions[1] != null ||
				mPressActions[2] != null;
	}

	public Boolean hasOngoingKeyCodes() {
		return mOnGoingKeyCodes.size() > 0;
	}

	public Boolean hasOngoingKeyCodes(final Integer keyCode) {
		return mOnGoingKeyCodes.contains(keyCode);
	}

	public Integer[] clearOngoingKeyCodes(final Boolean returList) {
		Integer[] keys = null; 

		if (returList) {
			keys = mOnGoingKeyCodes.toArray(new Integer[mOnGoingKeyCodes.size()]);
		}

		mOnGoingKeyCodes.clear();

		return keys;
	}

	public void addOngoingKeyCode(final Integer keyCode) {
		if (!mOnGoingKeyCodes.contains(keyCode)) {
			mOnGoingKeyCodes.add(keyCode);
		}
	}

	public void removeOngoingKeyCode(final Integer keyCode) {
		mOnGoingKeyCodes.remove(keyCode);
	}

	public String getAction(final Boolean isKeyDown) {
		return getAction(isKeyDown, mTapCount);
	}

	public String getAction(final Boolean isKeyDown, final Integer tapCount) {
		return isKeyDown ? 
				(tapCount < mPressActions.length ? mPressActions[tapCount] : null) : 
					(tapCount < mClickActions.length ? mClickActions[tapCount] : null);
	}

	public Integer getTapTimeout() {
		return mTapTimeout;
	}

	public Integer getPressTimeout() {
		return mPressTimeout;
	}

	public Long getDownTime() {
		return mDownTime;
	}

	public Long getEventTime() {
		return mEventTime;
	}

	public State getState() {
		return mState;
	}

	public Integer getTapCount() {
		return mTapCount;
	}

	public String getCurrentApplication() {
		return mCurrentApplication;
	}

	public void cancelEvent() {
		cancelEvent(false);
	}

	public void cancelEvent(final Boolean forcedReset) {
		synchronized (mLock) {
			if (mState == State.ONGOING || forcedReset) {
				mState = forcedReset ? State.PENDING : State.CANCELED;
			}
		}
	}

	public void invokeEvent() {
		synchronized (mLock) {
			if (mState == State.ONGOING) {
				mState = State.INVOKED;
			}
		}
	}

	public void invokeDefaultEvent(final Integer keyCode) {
		synchronized (mLock) {
			if (mState == State.ONGOING || mState == State.INVOKED_DEFAULT) {
				final EventKey key = getEventKey(keyCode);

				if (key != null) {
					mState = State.INVOKED_DEFAULT;
					key.mRepeatCount += 1;

				} else {
					mState = State.CANCELED;
				}
			}
		}
	}
}
