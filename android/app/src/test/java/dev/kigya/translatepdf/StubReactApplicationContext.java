package dev.kigya.translatepdf;

import android.content.Context;
import androidx.annotation.Nullable;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.CatalystInstance;
import com.facebook.react.bridge.JavaScriptContextHolder;
import com.facebook.react.bridge.JavaScriptModule;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.RuntimeExecutor;
import com.facebook.react.bridge.UIManager;
import com.facebook.react.bridge.queue.MessageQueueThread;
import com.facebook.react.bridge.queue.ReactQueueConfiguration;
import com.facebook.react.turbomodule.core.interfaces.CallInvokerHolder;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.lang.reflect.Proxy;

/** Minimal host context for exercising the native module without a JS runtime. */
class StubReactApplicationContext extends ReactApplicationContext {
  StubReactApplicationContext(Context context) { super(context); initializeTestQueues(); }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public void initializeTestQueues() {
    MessageQueueThread thread = (MessageQueueThread) Proxy.newProxyInstance(
        MessageQueueThread.class.getClassLoader(), new Class<?>[] {MessageQueueThread.class},
        (proxy, method, args) -> {
          switch (method.getName()) {
            case "runOnQueue": ((Runnable) args[0]).run(); return true;
            case "callOnQueue":
              CompletableFuture future = new CompletableFuture();
              try { future.complete(((Callable) args[0]).call()); }
              catch (Throwable error) { future.completeExceptionally(error); }
              return future;
            case "isOnThread": case "isIdle": return true;
            case "getPerfStats": return null;
            case "toString": return "Synchronous test React queue";
            default: return null;
          }
        });
    initializeMessageQueueThreads(new ReactQueueConfiguration() {
      @Override public MessageQueueThread getUIQueueThread() { return thread; }
      @Override public MessageQueueThread getNativeModulesQueueThread() { return thread; }
      @Override public MessageQueueThread getJSQueueThread() { return thread; }
      @Override public void destroy() {}
    });
  }

  @Override public <T extends JavaScriptModule> T getJSModule(Class<T> type) { return null; }
  @Override public <T extends NativeModule> boolean hasNativeModule(Class<T> type) { return false; }
  @Override public Collection<NativeModule> getNativeModules() { return Collections.emptyList(); }
  @Override public <T extends NativeModule> T getNativeModule(Class<T> type) { return null; }
  @Override public NativeModule getNativeModule(String name) { return null; }
  @Override public CatalystInstance getCatalystInstance() { return null; }
  @Override public boolean hasActiveCatalystInstance() { return false; }
  @Override public boolean hasActiveReactInstance() { return false; }
  @Override public boolean hasCatalystInstance() { return false; }
  @Override public boolean hasReactInstance() { return false; }
  @Nullable @Override public RuntimeExecutor getRuntimeExecutor() { return null; }
  @Override public void destroy() {}
  @Override public void handleException(Exception error) { throw new AssertionError(error); }
  @Override public boolean isBridgeless() { return false; }
  @Nullable @Override public JavaScriptContextHolder getJavaScriptContextHolder() { return null; }
  @Nullable @Override public CallInvokerHolder getJSCallInvokerHolder() { return null; }
  @Nullable @Override public UIManager getFabricUIManager() { return null; }
  @Nullable @Override public String getSourceURL() { return null; }
  @Override public void registerSegment(int segmentId, String path, Callback callback) {}
}
