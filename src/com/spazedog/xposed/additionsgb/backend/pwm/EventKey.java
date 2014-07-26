package com.spazedog.xposed.additionsgb.backend.pwm;

import android.view.KeyEvent;

public class EventKey {
	
	private Long mDownTime;
	private Integer mKeyCode;
	private Integer mFlags;
	private Integer mMetaState;
	private Integer mRepeatCount;
	private Boolean mIsPressed;
	private Boolean mIsOnGoing;
	
	private EventManager mManager;
	
	protected EventKey(EventManager manager) {
		mManager = manager;
	}
	
	protected void initiateInstance(Integer keyCode, Integer flags, Integer metaState, Long downTime) {
		mIsOnGoing = false;
		mRepeatCount = 0;
		mKeyCode = keyCode;
		mFlags = flags;
		mMetaState = metaState;
		mDownTime = downTime;
	}
	
	protected void updateInstance(Boolean pressed) {
		mIsPressed = pressed;
	}
	
	public Long getDownTime() {
		return mDownTime;
	}

	public Integer getPosition() {
		return mManager.getKeyCodePosition(mKeyCode);
	}

	public Integer getCode() {
		return mKeyCode;
	}

	public Integer getFlags() {
		return mFlags;
	}
	
	public Integer getMetaState() {
		return mMetaState;
	}

	public Integer getRepeatCount() {
		return mRepeatCount;
	}

	public Boolean isPressed() {
		return mIsPressed;
	}

	public Boolean isLastQueued() {
		return mManager.getLastQueuedKeyCode().equals(mKeyCode);
	}

	//public Boolean isOnGoing() {
	//	return mIsOnGoing;
	//}
	
	public void invokeAndRelease() {
		if (!mIsOnGoing) {
			for (int i=0; i < mManager.getKeyCount(); i++) {
				EventKey combo = mManager.getKeyAt(i);
				
				if (combo != null && !combo.mKeyCode.equals(mKeyCode) && !combo.mIsOnGoing) {
					combo.mIsOnGoing = true;
					combo.injectInputEvent(KeyEvent.ACTION_DOWN);
				}
			}
			
			this.injectInputEvent(KeyEvent.ACTION_MULTIPLE);
			
		} else {
			release();
		}
	}
	
	public void invoke() {
		if (mIsPressed) {
			if (!mIsOnGoing) {
				for (int i=0; i < mManager.getKeyCount(); i++) {
					EventKey combo = mManager.getKeyAt(i);
					
					if (combo != null && !combo.mKeyCode.equals(mKeyCode) && !combo.mIsOnGoing) {
						combo.mIsOnGoing = true;
						combo.injectInputEvent(KeyEvent.ACTION_DOWN);
					}
				}

				mIsOnGoing = true;
			}

			this.injectInputEvent(KeyEvent.ACTION_DOWN);
			mRepeatCount += 1;
		}
	}
	
	public void injectInputEvent(Integer action) {
		int repeatCount = (action == KeyEvent.ACTION_DOWN) ? mRepeatCount : 0;
		mManager.injectInputEvent(mKeyCode, action, 0L, 0L, repeatCount, mFlags, mMetaState);
	}
	
	public void release() {
		if (mIsOnGoing) {
			mIsOnGoing = false;
			this.injectInputEvent(KeyEvent.ACTION_UP);
		}
	}
}
