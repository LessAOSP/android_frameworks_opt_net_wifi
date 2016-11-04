/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi;

import android.content.Context;
import android.net.NetworkAgent;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.util.Log;

import com.android.internal.R;

/**
 * Class used to calculate scores for connected wifi networks and report it to the associated
 * network agent.
 * TODO: Add unit tests for this class.
*/
public class WifiScoreReport {
    private static final String TAG = "WifiScoreReport";

    // TODO: This score was hardcorded to 56.  Need to understand why after finishing code refactor
    private static final int STARTING_SCORE = 56;

    // TODO: Understand why these values are used
    private static final int MAX_BAD_LINKSPEED_COUNT = 6;
    private static final int SCAN_CACHE_VISIBILITY_MS = 12000;
    private static final int HOME_VISIBLE_NETWORK_MAX_COUNT = 6;
    private static final int SCAN_CACHE_COUNT_PENALTY = 2;
    private static final int AGGRESSIVE_HANDOVER_PENALTY = 6;
    private static final int MIN_SUCCESS_COUNT = 5;
    private static final int MAX_SUCCESS_COUNT_OF_STUCK_LINK = 3;
    private static final int MAX_STUCK_LINK_COUNT = 5;
    private static final int MIN_NUM_TICKS_AT_STATE = 1000;
    private static final int USER_DISCONNECT_PENALTY = 5;
    private static final int MAX_BAD_RSSI_COUNT = 7;
    private static final int BAD_RSSI_COUNT_PENALTY = 2;
    private static final int MAX_LOW_RSSI_COUNT = 1;
    private static final double MIN_TX_RATE_FOR_WORKING_LINK = 0.3;
    private static final int MIN_SUSTAINED_LINK_STUCK_COUNT = 1;
    private static final int LINK_STUCK_PENALTY = 2;
    private static final int BAD_LINKSPEED_PENALTY = 4;
    private static final int GOOD_LINKSPEED_BONUS = 4;

    // Device configs. The values are examples.
    private final int mThresholdMinimumRssi5;      // -82
    private final int mThresholdQualifiedRssi5;    // -70
    private final int mThresholdSaturatedRssi5;    // -57
    private final int mThresholdMinimumRssi24;     // -85
    private final int mThresholdQualifiedRssi24;   // -73
    private final int mThresholdSaturatedRssi24;   // -60
    private final int mBadLinkSpeed24;             //  6 Mbps
    private final int mBadLinkSpeed5;              // 12 Mbps
    private final int mGoodLinkSpeed24;            // 24 Mbps
    private final int mGoodLinkSpeed5;             // 36 Mbps
    private final boolean mEnableWifiCellularHandoverUserTriggeredAdjustment; // true

    private final WifiConfigManager mWifiConfigManager;
    private boolean mVerboseLoggingEnabled = false;

    // Cache of the last score report.
    private String mReport;
    private int mBadLinkspeedcount = 0;
    private boolean mReportValid = false;

    WifiScoreReport(Context context, WifiConfigManager wifiConfigManager) {
        // Fetch all the device configs.
        mThresholdMinimumRssi5 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_5GHz);
        mThresholdQualifiedRssi5 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_5GHz);
        mThresholdSaturatedRssi5 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_5GHz);
        mThresholdMinimumRssi24 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_24GHz);
        mThresholdQualifiedRssi24 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_24GHz);
        mThresholdSaturatedRssi24 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_24GHz);
        mBadLinkSpeed24 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_link_speed_24);
        mBadLinkSpeed5 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_link_speed_5);
        mGoodLinkSpeed24 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_good_link_speed_24);
        mGoodLinkSpeed5 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_good_link_speed_5);
        mEnableWifiCellularHandoverUserTriggeredAdjustment = context.getResources().getBoolean(
                R.bool.config_wifi_framework_cellular_handover_enable_user_triggered_adjustment);

        mWifiConfigManager = wifiConfigManager;
    }

    /**
     * Method returning the String representation of the last score report.
     *
     *  @return String score report
     */
    public String getLastReport() {
        return mReport;
    }

    /**
     * Method returning the bad link speed count at the time of the last score report.
     *
     *  @return int bad linkspeed count
     */
    public int getLastBadLinkspeedcount() {
        return mBadLinkspeedcount;
    }

    /**
     * Reset the last calculated score.
     */
    public void reset() {
        mBadLinkspeedcount = 0;
        mReport = "";
        mReportValid = false;
    }

    /**
     * Checks if the last report data is valid or not. This will be cleared when {@link #reset()} is
     * invoked.
     *
     * @return true if valid, false otherwise.
     */
    public boolean isLastReportValid() {
        return mReportValid;
    }

    /**
     * Enable/Disable verbose logging in score report generation.
     */
    public void enableVerboseLogging(boolean enable) {
        mVerboseLoggingEnabled = enable;
    }

    /**
     * Calculate wifi network score based on updated link layer stats and send the score to
     * the provided network agent.
     *
     * If the score has changed from the previous value, update the WifiNetworkAgent.
     *
     * Called periodically (POLL_RSSI_INTERVAL_MSECS) about every 3 seconds.
     *
     * @param wifiInfo WifiInfo instance pointing to the currently connected network.
     * @param networkAgent NetworkAgent to be notified of new score.
     * @param aggressiveHandover int current aggressiveHandover setting.
     * @param wifiMetrics for reporting our scores.
     */
    public void calculateAndReportScore(
            WifiInfo wifiInfo, NetworkAgent networkAgent, int aggressiveHandover,
            WifiMetrics wifiMetrics) {
        WifiInfo checkWifiInfo = new WifiInfo(wifiInfo);
        StringBuilder sb = new StringBuilder();

        int score = STARTING_SCORE;
        boolean isBadLinkspeed = (wifiInfo.is24GHz()
                && wifiInfo.getLinkSpeed() < mBadLinkSpeed24)
                || (wifiInfo.is5GHz() && wifiInfo.getLinkSpeed()
                < mBadLinkSpeed5);
        boolean isGoodLinkspeed = (wifiInfo.is24GHz()
                && wifiInfo.getLinkSpeed() >= mGoodLinkSpeed24)
                || (wifiInfo.is5GHz() && wifiInfo.getLinkSpeed()
                >= mGoodLinkSpeed5);

        if (isBadLinkspeed) {
            if (mBadLinkspeedcount < MAX_BAD_LINKSPEED_COUNT) {
                mBadLinkspeedcount++;
            }
        } else {
            if (mBadLinkspeedcount > 0) {
                mBadLinkspeedcount--;
            }
        }

        if (isBadLinkspeed) sb.append(" bl(").append(mBadLinkspeedcount).append(")");
        if (isGoodLinkspeed) sb.append(" gl");

        WifiConfiguration currentConfiguration =
                mWifiConfigManager.getConfiguredNetwork(wifiInfo.getNetworkId());
        ScanDetailCache scanDetailCache =
                mWifiConfigManager.getScanDetailCacheForNetwork(wifiInfo.getNetworkId());
        /**
         * We want to make sure that we use the 2.4GHz RSSI thresholds if
         * there are 2.4GHz scan results otherwise we end up lowering the score based on 5GHz values
         * which may cause a switch to LTE before roaming has a chance to try 2.4GHz
         * We also might unblacklist the configuation based on 2.4GHz
         * thresholds but joining 5GHz anyhow, and failing over to 2.4GHz because 5GHz is not good
         */
        boolean use24Thresholds = false;
        boolean homeNetworkBoost = false;
        if (currentConfiguration != null && scanDetailCache != null) {
            currentConfiguration.setVisibility(
                    scanDetailCache.getVisibility(SCAN_CACHE_VISIBILITY_MS));
            if (currentConfiguration.visibility != null) {
                if (currentConfiguration.visibility.rssi24 != WifiConfiguration.INVALID_RSSI
                        && currentConfiguration.visibility.rssi24
                        >= (currentConfiguration.visibility.rssi5 - SCAN_CACHE_COUNT_PENALTY)) {
                    use24Thresholds = true;
                }
            }
            if (scanDetailCache.size() <= HOME_VISIBLE_NETWORK_MAX_COUNT
                    && currentConfiguration.allowedKeyManagement.cardinality() == 1
                    && currentConfiguration.allowedKeyManagement
                            .get(WifiConfiguration.KeyMgmt.WPA_PSK)) {
                // A PSK network with less than 6 known BSSIDs
                // This is most likely a home network and thus we want to stick to wifi more
                homeNetworkBoost = true;
            }
        }
        if (homeNetworkBoost) sb.append(" hn");
        if (use24Thresholds) sb.append(" u24");

        int rssi = wifiInfo.getRssi() - AGGRESSIVE_HANDOVER_PENALTY * aggressiveHandover
                + (homeNetworkBoost ? WifiConfiguration.HOME_NETWORK_RSSI_BOOST : 0);
        sb.append(String.format(" rssi=%d ag=%d", rssi, aggressiveHandover));

        boolean is24GHz = use24Thresholds || wifiInfo.is24GHz();

        boolean isBadRSSI = (is24GHz && rssi < mThresholdMinimumRssi24)
                || (!is24GHz && rssi < mThresholdMinimumRssi5);
        boolean isLowRSSI = (is24GHz && rssi < mThresholdQualifiedRssi24)
                || (!is24GHz
                        && rssi < mThresholdQualifiedRssi5);
        boolean isHighRSSI = (is24GHz && rssi >= mThresholdSaturatedRssi24)
                || (!is24GHz
                        && rssi >= mThresholdSaturatedRssi5);

        if (isBadRSSI) sb.append(" br");
        if (isLowRSSI) sb.append(" lr");
        if (isHighRSSI) sb.append(" hr");

        if (mVerboseLoggingEnabled) {
            String rssiStatus = "";
            if (isBadRSSI) {
                rssiStatus += " badRSSI ";
            } else if (isHighRSSI) {
                rssiStatus += " highRSSI ";
            } else if (isLowRSSI) {
                rssiStatus += " lowRSSI ";
            }
            if (isBadLinkspeed) rssiStatus += " lowSpeed ";
            Log.d(TAG, " wifi scoring details freq=" + Integer.toString(wifiInfo.getFrequency())
                    + " speed=" + Integer.toString(wifiInfo.getLinkSpeed())
                    + " score=" + Integer.toString(wifiInfo.score) // Previous score
                    + rssiStatus
                    + " -> txbadrate=" + String.format("%.2f", wifiInfo.txBadRate)
                    + " txgoodrate=" + String.format("%.2f", wifiInfo.txSuccessRate)
                    + " txretriesrate=" + String.format("%.2f", wifiInfo.txRetriesRate)
                    + " rxrate=" + String.format("%.2f", wifiInfo.rxSuccessRate));
        }

        if ((wifiInfo.txBadRate >= 1)
                && (wifiInfo.txSuccessRate < MAX_SUCCESS_COUNT_OF_STUCK_LINK)
                && (isBadRSSI || isLowRSSI)) {
            // Link is stuck
            if (wifiInfo.linkStuckCount < MAX_STUCK_LINK_COUNT) {
                wifiInfo.linkStuckCount += 1;
            }
            sb.append(String.format(" ls+=%d", wifiInfo.linkStuckCount));
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, " bad link -> stuck count ="
                        + Integer.toString(wifiInfo.linkStuckCount));
            }
        } else if (wifiInfo.txBadRate < MIN_TX_RATE_FOR_WORKING_LINK) {
            if (wifiInfo.linkStuckCount > 0) {
                wifiInfo.linkStuckCount -= 1;
            }
            sb.append(String.format(" ls-=%d", wifiInfo.linkStuckCount));
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, " good link -> stuck count ="
                        + Integer.toString(wifiInfo.linkStuckCount));
            }
        }

        sb.append(String.format(" [%d", score));

        if (wifiInfo.linkStuckCount > MIN_SUSTAINED_LINK_STUCK_COUNT) {
            // Once link gets stuck for more than 3 seconds, start reducing the score
            score = score - LINK_STUCK_PENALTY * (wifiInfo.linkStuckCount - 1);
        }
        sb.append(String.format(",%d", score));

        if (isBadLinkspeed) {
            score -= BAD_LINKSPEED_PENALTY;
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, " isBadLinkspeed   ---> count=" + mBadLinkspeedcount
                        + " score=" + Integer.toString(score));
            }
        } else if ((isGoodLinkspeed) && (wifiInfo.txSuccessRate > 5)) {
            score += GOOD_LINKSPEED_BONUS; // So as bad rssi alone dont kill us
        }
        sb.append(String.format(",%d", score));

        if (isBadRSSI) {
            if (wifiInfo.badRssiCount < MAX_BAD_RSSI_COUNT) {
                wifiInfo.badRssiCount += 1;
            }
        } else if (isLowRSSI) {
            wifiInfo.lowRssiCount = MAX_LOW_RSSI_COUNT; // Dont increment the lowRssi count above 1
            if (wifiInfo.badRssiCount > 0) {
                // Decrement bad Rssi count
                wifiInfo.badRssiCount -= 1;
            }
        } else {
            wifiInfo.badRssiCount = 0;
            wifiInfo.lowRssiCount = 0;
        }

        score -= wifiInfo.badRssiCount * BAD_RSSI_COUNT_PENALTY + wifiInfo.lowRssiCount;
        sb.append(String.format(",%d", score));

        if (mVerboseLoggingEnabled) {
            Log.d(TAG, " badRSSI count" + Integer.toString(wifiInfo.badRssiCount)
                    + " lowRSSI count" + Integer.toString(wifiInfo.lowRssiCount)
                    + " --> score " + Integer.toString(score));
        }

        if (isHighRSSI) {
            score += 5;
            if (mVerboseLoggingEnabled) Log.d(TAG, " isHighRSSI       ---> score=" + score);
        }
        sb.append(String.format(",%d]", score));

        sb.append(String.format(" brc=%d lrc=%d", wifiInfo.badRssiCount, wifiInfo.lowRssiCount));

        //sanitize boundaries
        if (score > NetworkAgent.WIFI_BASE_SCORE) {
            score = NetworkAgent.WIFI_BASE_SCORE;
        }
        if (score < 0) {
            score = 0;
        }

        updateScoringState(checkWifiInfo, aggressiveHandover);
        int checkScore = calculateScore(checkWifiInfo, aggressiveHandover);

        if (score != checkScore) {
            Log.e(TAG, "recalculated score does not match: " +  score + "," + checkScore);
        }
        if (wifiInfo.linkStuckCount != checkWifiInfo.linkStuckCount) {
            Log.e(TAG, "recalculated linkStuckCount does not match: "
                    + wifiInfo.linkStuckCount  + "," + checkWifiInfo.linkStuckCount);
        }
        if (wifiInfo.badRssiCount != checkWifiInfo.badRssiCount) {
            Log.e(TAG, "recalculated badRssiCount does not match: "
                    + wifiInfo.badRssiCount  + "," + checkWifiInfo.badRssiCount);
        }
        if (wifiInfo.lowRssiCount != checkWifiInfo.lowRssiCount) {
            Log.e(TAG, "recalculated lowRssiCount does not match: "
                    + wifiInfo.lowRssiCount  + "," + checkWifiInfo.lowRssiCount);
        }

        //report score
        if (score != wifiInfo.score) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, " report new wifi score " + Integer.toString(score));
            }
            wifiInfo.score = score;
            if (networkAgent != null) {
                networkAgent.sendNetworkScore(score);
            }
        }

        mReport = sb.toString();
        mReportValid = true;
        wifiMetrics.incrementWifiScoreCount(score);
        Log.e(TAG, mReport); // fur debug
    }

    /**
     * Updates the state.
     */
    public void updateScoringState(WifiInfo wifiInfo, int aggressiveHandover) {
        int rssiThreshBad = mThresholdMinimumRssi24;
        int rssiThreshLow = mThresholdQualifiedRssi24;

        if (wifiInfo.is5GHz()) {
            if (!multiBandScanResults(wifiInfo)) {
                rssiThreshBad = mThresholdMinimumRssi5;
                rssiThreshLow = mThresholdQualifiedRssi5;
            }
        }

        int rssi =  wifiInfo.getRssi();
        if (aggressiveHandover != 0) {
            rssi -= AGGRESSIVE_HANDOVER_PENALTY * aggressiveHandover;
        }
        if (isHomeNetwork(wifiInfo)) {
            rssi += WifiConfiguration.HOME_NETWORK_RSSI_BOOST;
        }

        if ((wifiInfo.txBadRate >= 1)
                && (wifiInfo.txSuccessRate < MAX_SUCCESS_COUNT_OF_STUCK_LINK)
                && rssi < rssiThreshLow) {
            // Link is stuck
            if (wifiInfo.linkStuckCount < MAX_STUCK_LINK_COUNT) {
                wifiInfo.linkStuckCount += 1;
            }
        } else if (wifiInfo.txBadRate < MIN_TX_RATE_FOR_WORKING_LINK) {
            if (wifiInfo.linkStuckCount > 0) {
                wifiInfo.linkStuckCount -= 1;
            }
        }

        if (rssi < rssiThreshBad) {
            if (wifiInfo.badRssiCount < MAX_BAD_RSSI_COUNT) {
                wifiInfo.badRssiCount += 1;
            }
        } else if (rssi < rssiThreshLow) {
            wifiInfo.lowRssiCount = MAX_LOW_RSSI_COUNT; // Dont increment the lowRssi count above 1
            if (wifiInfo.badRssiCount > 0) {
                // Decrement bad Rssi count
                wifiInfo.badRssiCount -= 1;
            }
        } else {
            wifiInfo.badRssiCount = 0;
            wifiInfo.lowRssiCount = 0;
        }

    }

    /**
     * Calculates the score, without all the cruft.
     */
    public int calculateScore(WifiInfo wifiInfo, int aggressiveHandover) {
        int score = STARTING_SCORE;

        int rssiThreshSaturated = mThresholdSaturatedRssi24;
        int linkspeedThreshBad = mBadLinkSpeed24;
        int linkspeedThreshGood = mGoodLinkSpeed24;

        if (wifiInfo.is24GHz() != !(wifiInfo.is5GHz())) {
            throw new AssertionError("What is happening here?");
        }

        if (wifiInfo.is5GHz()) {
            if (!multiBandScanResults(wifiInfo)) {
                rssiThreshSaturated = mThresholdSaturatedRssi5;
            }
            linkspeedThreshBad = mBadLinkSpeed5;
            linkspeedThreshGood = mGoodLinkSpeed5;
        }

        int rssi =  wifiInfo.getRssi();
        if (aggressiveHandover != 0) {
            rssi -= AGGRESSIVE_HANDOVER_PENALTY * aggressiveHandover;
        }
        if (isHomeNetwork(wifiInfo)) {
            rssi += WifiConfiguration.HOME_NETWORK_RSSI_BOOST;
        }

        int linkSpeed = wifiInfo.getLinkSpeed();

        // Updates to wifiInfo.linkStuckCount skipped here

        if (wifiInfo.linkStuckCount > MIN_SUSTAINED_LINK_STUCK_COUNT) {
            // Once link gets stuck for more than 3 seconds, start reducing the score
            score = score - LINK_STUCK_PENALTY * (wifiInfo.linkStuckCount - 1);
        }

        if (linkSpeed < linkspeedThreshBad) {
            score -= BAD_LINKSPEED_PENALTY;
        } else if ((linkSpeed >= linkspeedThreshGood) && (wifiInfo.txSuccessRate > 5)) {
            score += GOOD_LINKSPEED_BONUS; // So as bad rssi alone doesn't kill us
        }

        // Updates to wifiInfo.badRssiCount skipped here

        score -= wifiInfo.badRssiCount * BAD_RSSI_COUNT_PENALTY + wifiInfo.lowRssiCount;

        if (rssi >= rssiThreshSaturated) score += 5;

        if (score > NetworkAgent.WIFI_BASE_SCORE) score = NetworkAgent.WIFI_BASE_SCORE;
        if (score < 0) score = 0;

        return score;
    }

    /**
     * Determines if we can see both 2.4GHz and 5GHz for current config
     */
    private boolean multiBandScanResults(WifiInfo wifiInfo) {
        WifiConfiguration currentConfiguration =
                mWifiConfigManager.getConfiguredNetwork(wifiInfo.getNetworkId());
        if (currentConfiguration == null) return false;
        ScanDetailCache scanDetailCache =
                mWifiConfigManager.getScanDetailCacheForNetwork(wifiInfo.getNetworkId());
        if (scanDetailCache == null) return false;
        // TODO(b/36364366): Nasty that we change state here...
        currentConfiguration.setVisibility(scanDetailCache.getVisibility(SCAN_CACHE_VISIBILITY_MS));
        if (currentConfiguration.visibility == null) return false;
        if (currentConfiguration.visibility.rssi24 == WifiConfiguration.INVALID_RSSI) return false;
        // TODO: this does not do exactly what is claimed!
        if (currentConfiguration.visibility.rssi24
                >= currentConfiguration.visibility.rssi5 - SCAN_CACHE_COUNT_PENALTY) {
            return true;
        }
        return false;
    }

    /**
     * Decides whether the current network is a "home" network
     */
    private boolean isHomeNetwork(WifiInfo wifiInfo) {
        WifiConfiguration currentConfiguration =
                mWifiConfigManager.getConfiguredNetwork(wifiInfo.getNetworkId());
        if (currentConfiguration == null) return false;
        // TODO: This seems like it will only return true for really old routers!
        if (currentConfiguration.allowedKeyManagement.cardinality() != 1) return false;
        if (!currentConfiguration.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)) {
            return false;
        }
        ScanDetailCache scanDetailCache =
                mWifiConfigManager.getScanDetailCacheForNetwork(wifiInfo.getNetworkId());
        if (scanDetailCache == null) return false;
        if (scanDetailCache.size() <= HOME_VISIBLE_NETWORK_MAX_COUNT) {
            return true;
        }
        return false;
    }
}
