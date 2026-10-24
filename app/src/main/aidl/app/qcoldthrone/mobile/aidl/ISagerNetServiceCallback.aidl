package app.qcoldthrone.mobile.aidl;

import app.qcoldthrone.mobile.aidl.SpeedDisplayData;
import app.qcoldthrone.mobile.aidl.TrafficDataBatch;

oneway interface ISagerNetServiceCallback {
  void stateChanged(int state, String profileName, String msg);
  void cbSpeedUpdate(in SpeedDisplayData stats);
  void cbTrafficUpdate(in TrafficDataBatch stats);
  void cbSelectorUpdate(long id);
  void cbAutoSelectorUpdate(String json);
}
