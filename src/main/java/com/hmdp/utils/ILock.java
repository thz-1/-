package com.hmdp.utils;

/**
 * @author thz
 */
public interface ILock{

    boolean tryLock(long timeoutSec);

    void unlock();
}
