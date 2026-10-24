package app.qcoldthrone.mobile.aidl;

import app.qcoldthrone.mobile.aidl.ISubscriptionCallback;
import app.qcoldthrone.mobile.aidl.ITestSessionCallback;
import app.qcoldthrone.mobile.bg.test.TestSpec;

interface ICoreService {
  // Test sessions: returns the session id, -1 while another session runs; stopTests stops the running one.
  int startTest(in TestSpec spec, ITestSessionCallback cb);
  void stopTests();
  int runningTestSession();

  // Subscriptions (desktop GroupUpdater queue).
  void refreshGroup(long gid, boolean showDiff);
  void refreshAll(boolean onlyAllowed);
  long subscribeUrl(String url, String name, boolean autoUpdate, boolean allowInsecureRequests);
  void importUrl(String url, long gid);
  void importFile(String path, long gid);
  String groupAction(long gid, String action);
  void registerSubscriptionCallback(ISubscriptionCallback cb);
  void unregisterSubscriptionCallback(ISubscriptionCallback cb);

  // WARP: blocks up to 10 s + 10 s per API host; returns JSON (see WarpRegistration).
  String warpRegister(String tunnelType, String proxy, in String[] apiHosts);
}
