package com.peterwang.androidimitationtoys.asyntask;

import android.util.Log;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 仿造 AsyTask
 *
 * @author peter_wang
 * @create-time 15/11/12 16:57
 */
public abstract class MyAsynTask<Params, Result> {
    private static final String TAG = MyAsynTask.class.getSimpleName();

    private static final int CPU_NUM = Runtime.getRuntime().availableProcessors();
    /**
     * 对于计算密集的任务，在拥有 N 个处理器的系统上，当线程池大小为 N+1 时，通常能实现最优的利用率。
     */
    private static final int CORE_POOL_SIZE = CPU_NUM + 1;
    private static final int MAX_POOL_SIZE = 2 * CPU_NUM + 1;
    private static final int KEEP_ALIVE_TIME = 1;
    private static final BlockingDeque<Runnable> THREAD_BLOCKING_DEQUE = new LinkedBlockingDeque<>(128);
    /**
     * 线程创造器，主要在创建的时候设置部分信息，比如给线程编号命名等
     */
    private static final ThreadFactory mThreadFactory = new ThreadFactory() {
        private AtomicInteger mThreadIndex = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "MyAsynTask #" + mThreadIndex.getAndIncrement());
        }
    };

    private static final Executor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(CORE_POOL_SIZE, MAX_POOL_SIZE,
            KEEP_ALIVE_TIME, TimeUnit.SECONDS, THREAD_BLOCKING_DEQUE, mThreadFactory);

    /**
     * 线程执行线程池，默认位单一线程池，允许外部通过{@link #setDefaultExecutor
     * setDefaultExecutor}或{@link #useThreadPoolExecutor useThreadPoolExecutor}修改，
     * 设置为volatile防止多线程并发执行修改
     */
    private volatile static Executor mActualExecutor = new SerialExecutor();
    /**
     * 线程执行状态，初始化是未执行状态，设置为volatile防止多线程并发执行execute，保证线程状态mCurrentStatus的可见性
     */
    private volatile Status mCurrentStatus = Status.PENDING;

    private final TaskCallable mTaskCallable;
    private final FutureTask<Result> mFutureTask;

    /**
     * 线程是否取消
     * 知识点补充：volatile仅仅用来保证该变量对所有线程的可见性，但不保证原子性，
     * 不要将volatile用在getAndOperate场合（这种场合不原子，需要再加锁，或者用atomic变量），仅仅set或者get的场景是适合volatile的。
     */
    private final AtomicBoolean isCancelled = new AtomicBoolean();

    /**
     * 单一线程池，只能同时执行一个线程，顺序执行加入的线程
     */
    private static final class SerialExecutor implements Executor {

        private Deque<Runnable> mRunnableDeque = new ArrayDeque<>();
        private Runnable mToExecuteRunnable;

        @Override
        public synchronized void execute(final Runnable runnable) {
            //先把线程放入队列，先进先出，单向执行，执行完一个再调用executeNext执行下一个
            mRunnableDeque.offer(new Runnable() {
                @Override
                public void run() {
                    try {
                        runnable.run();
                    } finally {
                        executeNext();
                    }
                }
            });

            //未执行任何线程，开始执行第一个线程
            if (mToExecuteRunnable == null) {
                executeNext();
            }
        }

        private void executeNext() {
            if ((mToExecuteRunnable = mRunnableDeque.poll()) != null) {
                THREAD_POOL_EXECUTOR.execute(mToExecuteRunnable);
            }
        }
    }

    /**
     * 线程执行状态：未开始、进行中、已结束
     */
    public enum Status {
        PENDING,
        RUNNING,
        FINISHED
    }

    public MyAsynTask() {
        mTaskCallable = new TaskCallable<Params, Result>() {
            @Override
            public Result call() throws Exception {
                return doInBackground(mParams);
            }
        };

        mFutureTask = new FutureTask<Result>(mTaskCallable) {
            @Override
            protected void done() {
                try {
                    finish(get());
                } catch (InterruptedException e) {
                    Log.w(TAG, "the task is interrupted.");
                } catch (ExecutionException e) {
                    throw new RuntimeException("An error occured while executing doInBackground()",
                            e.getCause());
                }
            }
        };
    }

    /**
     * AsynTask在sdk1.6之前是单一线程池，但同时执行多个线程时，允许一个线程执行其他线程等待事件较长，
     * 1.6开始建立多线程并发线程池，可以有5个线程同时执行，但又出现并发性问题，比如线程过多超过BlockingDeque和MAX_POOL_SIZE容量会崩溃，
     * 3.0开始又回归单一线程池，但同时开放线程池的设置，允许外部设置线程池参数
     *
     * @param exec 线程池
     */
    public static void setDefaultExecutor(Executor exec) {
        mActualExecutor = exec;
    }

    /**
     * 允许设置不采用单一线程池运行，回归3.0之前的多线程并发线程池
     */
    public static void useThreadPoolExecutor() {
        mActualExecutor = THREAD_POOL_EXECUTOR;
    }

    public final boolean cancel(boolean mayInterruptIfRunning) {
        isCancelled.set(true);
        return mFutureTask.cancel(mayInterruptIfRunning);
    }

    private boolean isCancelled() {
        return isCancelled.get();
    }

    private void finish(Result result) {
        if (!isCancelled()) {
            onPostExecute(result);
        }
        mCurrentStatus = Status.FINISHED;
    }

    public void execute(Params... params) {
        if (mCurrentStatus == Status.RUNNING) {
            throw new IllegalThreadStateException("the task is running,can not execute again.");
        } else if (mCurrentStatus == Status.FINISHED) {
            throw new IllegalThreadStateException("the task is finished,can not execute again.");
        }

        mCurrentStatus = Status.RUNNING;
        onPreExecute();

        mTaskCallable.mParams = params;
        mActualExecutor.execute(mFutureTask);
    }

    /**
     * 线程执行
     *
     * @param params 线程执行参数
     * @return 线程完的返回值
     */
    protected abstract Result doInBackground(Params... params);

    /**
     * 线程执行前的操作
     */
    protected void onPreExecute() {
    }

    /**
     * 线程执行后的操作
     *
     * @param result 线程执行完的返回值
     */
    protected void onPostExecute(Result result) {
    }

    private static abstract class TaskCallable<Params, Result> implements Callable<Result> {
        Params mParams[];
    }
}
