package com.hmdp.utils;

public interface ILock {
    /**
     *
     * @param timeoutSec 锁过期时间
     * @return
     */
    boolean tryLock(long timeoutSec);
    void unLock();
}
