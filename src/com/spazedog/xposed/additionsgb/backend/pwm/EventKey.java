package com.spazedog.xposed.additionsgb.backend.pwm;

import com.spazedog.xposed.additionsgb.backend.pwm.EventManager.Priority;

public class EventKey {

	private final Priority mPriority;
	protected Integer mKeyCode = 0;
	protected Integer mPolicyFlags = 0;
	protected Boolean mIsKeyDown = false;

	public EventKey(final Priority priority) {
		mPriority = priority;
	}

	public Priority getPriority() {
		return mPriority;
	}

	public Integer getKeyCode() {
		return mKeyCode;
	}

	public Integer getPolicFlags() {
		return mPolicyFlags;
	}

	public Boolean isKeyDown() {
		return mIsKeyDown;
	}
}
