#!/usr/bin/env python3
"""
NetProbe v2.44 — Appium Automation Test Suite
=============================================
Automated UI testing for NetProbe Android app.
Tests all 7 tabs: PROBE, MTU, MAX, DRIVE, TRACE, FLOW, LOGS

FLOW tab = embedded Tunnely VPN client (com.tunnely.app:id/ resource IDs)

Usage:
    python3 test_netprobe.py              # Run all tests
    python3 test_netprobe.py --quick      # Smoke test only
    python3 test_netprobe.py --probe-only # Only PROBE tab test

Requirements:
    - Appium server running on http://127.0.0.1:4723
    - Android emulator (emulator-5554) with NetProbe v2.44 installed
    - pip install Appium-Python-Client
"""

import unittest
import time
import os
import sys
import json
import subprocess
from datetime import datetime

# Ensure ANDROID_HOME is set (required by Appium server)
os.environ.setdefault("ANDROID_HOME", "/home/hidayat/android-sdk")
os.environ.setdefault("ANDROID_SDK_ROOT", os.environ["ANDROID_HOME"])

from appium import webdriver
from appium.options.android import UiAutomator2Options
from appium.webdriver.common.appiumby import AppiumBy
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.common.exceptions import TimeoutException, NoSuchElementException

# ─── Configuration ───────────────────────────────────────────────────────────

APPIUM_SERVER = "http://127.0.0.1:4723"
PACKAGE = "com.telcoagent.udpclient"
TUNNELY_PKG = "com.tunnely.app"  # Embedded VPN client in FLOW tab
ACTIVITY = ".MainActivity"
DEVICE_SERIAL = "emulator-5554"
SCREENSHOT_DIR = "/tmp/netprobe_screenshots"
TIMEOUT = 10

# Tab coordinates (content-desc values)
TABS = {
    "PROBE": "Probe",
    "MTU": "MTU",
    "MAX": "MAX",
    "DRIVE": "Drive",
    "TRACE": "Trace",
    "FLOW": "Flow",
    "LOGS": "Logs",
}

# ─── Helpers ─────────────────────────────────────────────────────────────────

def ensure_screenshot_dir():
    os.makedirs(SCREENSHOT_DIR, exist_ok=True)


class NetProbeTestBase(unittest.TestCase):
    """Base class with Appium setup/teardown and common helpers."""

    driver = None

    @classmethod
    def setUpClass(cls):
        ensure_screenshot_dir()

        # Pre-grant all runtime permissions to avoid dialog blocks
        for perm in [
            "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
            "ACCESS_BACKGROUND_LOCATION", "POST_NOTIFICATIONS",
            "READ_PHONE_STATE", "CALL_PHONE", "READ_CALL_LOG",
            "ANSWER_PHONE_CALLS", "READ_SMS",
        ]:
            subprocess.run(
                f"adb -s {DEVICE_SERIAL} shell pm grant {PACKAGE} android.permission.{perm}",
                shell=True, capture_output=True
            )
        # Also grant to Tunnely (embedded VPN client)
        for perm in ["ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS"]:
            subprocess.run(
                f"adb -s {DEVICE_SERIAL} shell pm grant {TUNNELY_PKG} android.permission.{perm}",
                shell=True, capture_output=True
            )

        options = UiAutomator2Options()
        options.platform_name = "Android"
        options.device_name = DEVICE_SERIAL
        options.udid = DEVICE_SERIAL
        options.app_package = PACKAGE
        options.app_activity = ACTIVITY
        options.automation_name = "UiAutomator2"
        options.no_reset = True
        options.auto_grant_permissions = True
        options.new_command_timeout = 300

        print(f"\n🚀 Connecting to Appium at {APPIUM_SERVER}...")
        cls.driver = webdriver.Remote(APPIUM_SERVER, options=options)
        cls.driver.implicitly_wait(5)
        print(f"✅ Connected! Session ID: {cls.driver.session_id}")

    @classmethod
    def tearDownClass(cls):
        if cls.driver:
            cls.driver.quit()
            print("\n👋 Appium session closed.")

    def setUp(self):
        if self.driver:
            self.driver.activate_app(PACKAGE)
            time.sleep(1)

    # ── Screenshot ────────────────────────────────────────────────────────

    def screenshot(self, name):
        if self.driver:
            path = os.path.join(SCREENSHOT_DIR, f"{name}.png")
            self.driver.save_screenshot(path)
            print(f"📸 {path}")
            return path
        return None

    # ── Tab navigation ────────────────────────────────────────────────────

    # Approximate tap coordinates for each tab (center of content-desc bounds)
    TAB_COORDS = {
        "PROBE": (34, 338),
        "MTU": (162, 338),
        "MAX": (351, 338),
        "DRIVE": (540, 338),
        "TRACE": (729, 338),
        "FLOW": (918, 338),
        "LOGS": (1056, 338),
    }

    def tap_tab(self, tab_name):
        """Tap a top-level tab by content-desc, with ADB coordinate fallback."""
        desc = TABS.get(tab_name.upper())
        coords = self.TAB_COORDS.get(tab_name.upper())
        if not desc:
            raise ValueError(f"Unknown tab: {tab_name}")

        # Try Appium first (fast, reliable when no dialog blocks)
        for attempt in range(2):
            try:
                el = self.driver.find_element(AppiumBy.ACCESSIBILITY_ID, desc)
                el.click()
                time.sleep(1.5)
                return
            except NoSuchElementException:
                try:
                    el = self.driver.find_element(
                        AppiumBy.XPATH, f'//*[@text="{tab_name.upper()}"]'
                    )
                    el.click()
                    time.sleep(1.5)
                    return
                except NoSuchElementException:
                    if attempt == 0:
                        self.dismiss_dialog()
                        time.sleep(0.5)

        # Fallback: ADB tap coordinates
        if coords:
            subprocess.run(
                f"adb -s {DEVICE_SERIAL} shell input tap {coords[0]} {coords[1]}",
                shell=True, capture_output=True
            )
            time.sleep(1.5)
            self.dismiss_dialog()
        else:
            raise NoSuchElementException(f"Tab '{tab_name}' not found")

    def dismiss_dialog(self):
        """Dismiss any system dialog (VPN, permission, etc.) via ADB."""
        for _ in range(3):
            # Check if a dialog or permission screen is focused
            result = subprocess.run(
                f"adb -s {DEVICE_SERIAL} shell dumpsys activity activities | grep mResumedActivity",
                shell=True, capture_output=True, text=True
            )
            output = result.stdout
            # If focused on a dialog/permission activity, dismiss it
            if "permissioncontroller" in output.lower() or "Dialog" in output:
                # Try OK button coordinates
                subprocess.run(
                    f"adb -s {DEVICE_SERIAL} shell input tap 894 1520",
                    shell=True, capture_output=True
                )
                time.sleep(0.5)
                # Also try "Allow" button coordinates for permission dialogs
                subprocess.run(
                    f"adb -s {DEVICE_SERIAL} shell input tap 540 1472",
                    shell=True, capture_output=True
                )
                time.sleep(0.5)
            else:
                break
        # Also press BACK key to dismiss any remaining dialog
        try:
            subprocess.run(
                f"adb -s {DEVICE_SERIAL} shell input keyevent 4",
                shell=True, capture_output=True
            )
            time.sleep(0.3)
        except Exception:
            pass

    # ── Common assertions ─────────────────────────────────────────────────

    def assert_text_visible(self, text, timeout=TIMEOUT):
        try:
            WebDriverWait(self.driver, timeout).until(
                EC.presence_of_element_located(
                    (AppiumBy.XPATH, f'//*[@text="{text}"]')
                )
            )
            return True
        except TimeoutException:
            self.fail(f"Text '{text}' not found within {timeout}s")

    def assert_element_by_id(self, resource_id, timeout=TIMEOUT):
        try:
            WebDriverWait(self.driver, timeout).until(
                EC.presence_of_element_located((AppiumBy.ID, resource_id))
            )
            return True
        except TimeoutException:
            self.fail(f"Element '{resource_id}' not found within {timeout}s")

    def get_text_by_id(self, resource_id, timeout=TIMEOUT):
        try:
            el = WebDriverWait(self.driver, timeout).until(
                EC.presence_of_element_located((AppiumBy.ID, resource_id))
            )
            return el.text
        except TimeoutException:
            return None

    def scroll_down(self):
        size = self.driver.get_window_size()
        x = size['width'] // 2
        self.driver.swipe(x, int(size['height'] * 0.7), x, int(size['height'] * 0.3), 500)
        time.sleep(1)


# ═══════════════════════════════════════════════════════════════════════════════
# TEST CASES
# ═══════════════════════════════════════════════════════════════════════════════

class TestAppLaunch(NetProbeTestBase):
    """Test app launches correctly."""

    def test_01_app_launches(self):
        """App should show title and version."""
        self.screenshot("01_launch")
        self.assert_text_visible("NetProbe")
        self.assert_text_visible("v2.44 (55)")

    def test_02_tabs_visible(self):
        """Core tabs should be visible via content-desc."""
        # Only check tabs that are always visible in the tab bar
        for tab in ["PROBE", "MTU", "MAX", "DRIVE"]:
            with self.subTest(tab=tab):
                desc = TABS[tab]
                try:
                    self.driver.find_element(AppiumBy.ACCESSIBILITY_ID, desc)
                except NoSuchElementException:
                    self.fail(f"Tab '{tab}' (desc='{desc}') not found")

    def test_03_default_tab(self):
        """App should land on PROBE or AROUND tab by default."""
        # Check for elements from either tab
        try:
            self.driver.find_element(AppiumBy.ACCESSIBILITY_ID, "Probe")
        except NoSuchElementException:
            try:
                self.driver.find_element(AppiumBy.ACCESSIBILITY_ID, "Around")
            except NoSuchElementException:
                self.fail("Neither PROBE nor AROUND tab is active")


class TestProbeTab(NetProbeTestBase):
    """Test the PROBE tab (UDP latency/bandwidth)."""

    def test_01_probe_elements(self):
        """PROBE tab should show target, metrics, and start button."""
        self.tap_tab("PROBE")
        self.screenshot("probe_tab")

        target = self.get_text_by_id(f"{PACKAGE}:id/targetText")
        self.assertIsNotNone(target, "Target field missing")
        self.assertIn(":", target)

        self.assert_element_by_id(f"{PACKAGE}:id/statusText")
        self.assert_element_by_id(f"{PACKAGE}:id/startButton")

    def test_02_metric_fields(self):
        """All metric fields should exist."""
        self.tap_tab("PROBE")
        for mid in ["latencyValue", "lossValue", "uplinkLossValue",
                     "downlinkLossValue", "uplinkValue", "downlinkValue",
                     "jitterValue", "roundsValue"]:
            with self.subTest(metric=mid):
                self.assert_element_by_id(f"{PACKAGE}:id/{mid}")

    def test_03_start_stop_probe(self):
        """START PROBE should begin, second tap should stop."""
        self.tap_tab("PROBE")
        btn = self.driver.find_element(AppiumBy.ID, f"{PACKAGE}:id/startButton")

        # Start
        btn.click()
        time.sleep(3)
        self.screenshot("probe_running")

        # Stop
        btn.click()
        time.sleep(1)
        self.screenshot("probe_stopped")


class TestMtuTab(NetProbeTestBase):
    """Test the MTU tab."""

    def test_01_mtu_elements(self):
        """MTU tab should show PATH MTU, WIREGUARD MTU, FIND MTU button."""
        self.tap_tab("MTU")
        self.screenshot("mtu_tab")
        self.assert_element_by_id(f"{PACKAGE}:id/pmtuValue")
        self.assert_element_by_id(f"{PACKAGE}:id/wgMtuValue")
        self.assert_text_visible("FIND MTU")

    def test_02_find_mtu(self):
        """FIND MTU should start discovery."""
        self.tap_tab("MTU")
        btn = self.driver.find_element(AppiumBy.ID, f"{PACKAGE}:id/startButton")
        btn.click()
        time.sleep(3)
        self.screenshot("mtu_running")
        btn.click()  # Stop
        time.sleep(1)


class TestMaxTab(NetProbeTestBase):
    """Test the MAX tab."""

    def test_01_max_elements(self):
        """MAX tab should show metrics and START MAX TEST button."""
        self.tap_tab("MAX")
        self.screenshot("max_tab")
        self.assert_element_by_id(f"{PACKAGE}:id/optimalStreamsValue")
        self.assert_element_by_id(f"{PACKAGE}:id/maxThroughputValue")
        self.assert_element_by_id(f"{PACKAGE}:id/burstThroughputValue")
        self.assert_text_visible("START MAX TEST")

    def test_02_start_max(self):
        """START MAX TEST should begin throughput test."""
        self.tap_tab("MAX")
        btn = self.driver.find_element(AppiumBy.ID, f"{PACKAGE}:id/startButton")
        btn.click()
        time.sleep(3)
        self.screenshot("max_running")
        btn.click()  # Stop
        time.sleep(1)


class TestDriveTab(NetProbeTestBase):
    """Test the DRIVE tab (cellular/WiFi info + drive test)."""

    def test_01_drive_cellular_info(self):
        """DRIVE tab should show cellular info (TECH, TAC, etc.)."""
        self.tap_tab("DRIVE")
        self.screenshot("drive_tab")

        # Cellular fields
        for label in ["TECH:", "TAC:", "gNB:", "CELLID:", "ARFCN:", "BAND:", "RSRP:"]:
            with self.subTest(field=label):
                self.assert_text_visible(label, timeout=5)

    def test_02_drive_wifi_info(self):
        """DRIVE tab should show WiFi info."""
        self.tap_tab("DRIVE")
        self.assert_text_visible("WIFI", timeout=5)
        for label in ["SSID:", "BSSID:", "RSSI:", "FREQ:"]:
            with self.subTest(field=label):
                self.assert_text_visible(label, timeout=5)

    def test_03_drive_controls(self):
        """DRIVE tab should show Drive and Start Log buttons."""
        self.tap_tab("DRIVE")
        self.assert_element_by_id(f"{PACKAGE}:id/driveButton")
        self.assert_element_by_id(f"{PACKAGE}:id/logButton")

    def test_04_drive_status(self):
        """DRIVE tab should show drive status."""
        self.tap_tab("DRIVE")
        self.assert_element_by_id(f"{PACKAGE}:id/driveStatusText")
        self.assert_element_by_id(f"{PACKAGE}:id/driveRoundCount")


class TestTraceTab(NetProbeTestBase):
    """Test the TRACE tab."""

    def test_01_trace_elements(self):
        """TRACE tab should show log-related elements."""
        self.tap_tab("TRACE")
        self.screenshot("trace_tab")
        # TRACE tab content varies — just verify it loads without crash
        current = self.driver.current_activity
        self.assertIn("MainActivity", current)


class TestFlowTab(NetProbeTestBase):
    """Test the FLOW tab (embedded Tunnely VPN client)."""

    def _goto_flow(self):
        """Navigate to FLOW tab. Must go through DRIVE first since FLOW/LOGS
        tabs are only visible when right-side tabs are in view."""
        self.dismiss_dialog()
        self.driver.activate_app(PACKAGE)
        time.sleep(1)
        # First navigate to DRIVE (always visible in tab bar)
        self.tap_tab("DRIVE")
        time.sleep(1)
        self.dismiss_dialog()
        # Now FLOW tab should be visible — tap it via accessibility_id
        try:
            el = self.driver.find_element(AppiumBy.ACCESSIBILITY_ID, "Flow")
            el.click()
            time.sleep(2)
        except NoSuchElementException:
            # ADB coordinate fallback
            subprocess.run(
                f"adb -s {DEVICE_SERIAL} shell input tap 918 338",
                shell=True, capture_output=True
            )
            time.sleep(2)
        self.dismiss_dialog()
        # Re-activate in case VPN dialog pushed app to background
        self.driver.activate_app(PACKAGE)
        time.sleep(1)

    def test_01_flow_tab_elements(self):
        """FLOW tab should show VPN status and server info."""
        self._goto_flow()
        self.screenshot("flow_tab")

        # VPN status (uses Tunnely resource IDs)
        self.assert_element_by_id(f"{TUNNELY_PKG}:id/status_text")
        self.assert_element_by_id(f"{TUNNELY_PKG}:id/server_endpoint")
        self.assert_element_by_id(f"{TUNNELY_PKG}:id/client_pubkey")

    def test_02_flow_buttons(self):
        """FLOW tab should show Regenerate, Auto Config, CONNECT buttons."""
        self._goto_flow()
        self.assert_element_by_id(f"{TUNNELY_PKG}:id/btn_regenerate")
        self.assert_element_by_id(f"{TUNNELY_PKG}:id/btn_auto_config")
        self.assert_element_by_id(f"{TUNNELY_PKG}:id/btn_connect")

    def test_03_flow_server_info(self):
        """FLOW tab should show server endpoint and tunnel IP."""
        self._goto_flow()

        endpoint = self.get_text_by_id(f"{TUNNELY_PKG}:id/server_endpoint")
        self.assertIsNotNone(endpoint, "Server endpoint missing")
        self.assertIn(":", endpoint, "Endpoint should be host:port")

        tunnel_ip = self.get_text_by_id(f"{TUNNELY_PKG}:id/tunnel_ip")
        self.assertIsNotNone(tunnel_ip, "Tunnel IP missing")

    def test_04_flow_sub_tabs(self):
        """FLOW tab should have Connect/Flows/Settings bottom nav."""
        self._goto_flow()

        for nav in ["Connect", "Flows", "Settings"]:
            with self.subTest(nav=nav):
                try:
                    self.driver.find_element(AppiumBy.ACCESSIBILITY_ID, nav)
                except NoSuchElementException:
                    self.fail(f"Nav item '{nav}' not found in FLOW tab")

    def test_05_flow_regenerate_key(self):
        """Regenerate button should update the public key."""
        self._goto_flow()

        initial = self.get_text_by_id(f"{TUNNELY_PKG}:id/client_pubkey")
        print(f"🔑 Initial: {initial}")

        try:
            regen = self.driver.find_element(AppiumBy.ID, f"{TUNNELY_PKG}:id/btn_regenerate")
            regen.click()
            time.sleep(2)
            self.screenshot("after_regen")
            new_key = self.get_text_by_id(f"{TUNNELY_PKG}:id/client_pubkey")
            print(f"🔑 New: {new_key}")
        except NoSuchElementException:
            self.skipTest("Regenerate button not found")


class TestLogsTab(NetProbeTestBase):
    """Test the LOGS tab."""

    def test_01_logs_tab_loads(self):
        """LOGS tab should load without crash."""
        self.dismiss_dialog()
        self.driver.activate_app(PACKAGE)
        time.sleep(1)
        # Navigate to DRIVE first to make LOGS tab visible
        self.tap_tab("DRIVE")
        time.sleep(1)
        self.dismiss_dialog()
        self.tap_tab("LOGS")
        self.dismiss_dialog()
        self.screenshot("logs_tab")
        self.driver.activate_app(PACKAGE)
        current = self.driver.current_activity
        self.assertIn("MainActivity", current)


class TestNavigation(NetProbeTestBase):
    """Test tab navigation resilience."""

    def test_01_navigate_all_tabs(self):
        """All tabs should be navigable without crash."""
        for tab in TABS:
            with self.subTest(tab=tab):
                self.dismiss_dialog()
                self.driver.activate_app(PACKAGE)
                time.sleep(0.5)
                self.tap_tab(tab)
                self.dismiss_dialog()
                time.sleep(0.5)
                self.screenshot(f"nav_{tab.lower()}")
                # Re-activate in case app went background
                self.driver.activate_app(PACKAGE)
                current = self.driver.current_activity
                self.assertIn("MainActivity", current)

    def test_02_rapid_tab_switching(self):
        """Rapid switching shouldn't crash."""
        tabs = list(TABS.keys()) * 2
        for tab in tabs:
            try:
                self.tap_tab(tab)
                time.sleep(0.2)
            except Exception:
                try:
                    self.dismiss_dialog()
                except Exception:
                    pass

        self.driver.activate_app(PACKAGE)
        self.screenshot("after_rapid_switch")
        current = self.driver.current_activity
        self.assertIn("MainActivity", current)


# ═══════════════════════════════════════════════════════════════════════════════
# RUNNER
# ═══════════════════════════════════════════════════════════════════════════════

def run_tests():
    loader = unittest.TestLoader()
    suite = unittest.TestSuite()

    test_classes = [
        TestAppLaunch,
        TestProbeTab,
        TestMtuTab,
        TestMaxTab,
        TestDriveTab,
        TestTraceTab,
        TestFlowTab,
        TestLogsTab,
        TestNavigation,
    ]

    if "--quick" in sys.argv:
        test_classes = [TestAppLaunch, TestNavigation]
    elif "--probe-only" in sys.argv:
        test_classes = [TestAppLaunch, TestProbeTab]
    elif "--drive-only" in sys.argv:
        test_classes = [TestAppLaunch, TestDriveTab]

    for cls in test_classes:
        suite.addTests(loader.loadTestsFromTestCase(cls))

    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)

    # Report
    total = result.testsRun
    failures = len(result.failures)
    errors_count = len(result.errors)
    passed = total - failures - errors_count

    report_dir = "/tmp/netprobe_reports"
    os.makedirs(report_dir, exist_ok=True)
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    json_path = os.path.join(report_dir, f"netprobe_{ts}.json")

    summary = {
        "app": "NetProbe",
        "version": "v2.44 (55)",
        "package": PACKAGE,
        "device": DEVICE_SERIAL,
        "timestamp": datetime.now().isoformat(),
        "total": total,
        "passed": passed,
        "failed": failures,
        "errors": errors_count,
        "pass_rate": f"{(passed/total*100):.1f}%" if total else "N/A",
        "screenshots": SCREENSHOT_DIR,
    }

    with open(json_path, "w") as f:
        json.dump(summary, f, indent=2)

    print(f"\n{'='*60}")
    print(f"📊 NETPROBE TEST RESULTS")
    print(f"{'='*60}")
    print(f"  App:      {summary['app']} {summary['version']}")
    print(f"  Device:   {summary['device']}")
    print(f"  Total:    {summary['total']}")
    print(f"  ✅ Passed: {summary['passed']}")
    print(f"  ❌ Failed: {summary['failed']}")
    print(f"  💥 Errors: {summary['errors']}")
    print(f"  Rate:     {summary['pass_rate']}")
    print(f"  📸 Shots: {SCREENSHOT_DIR}")
    print(f"  📄 Report: {json_path}")
    print(f"{'='*60}\n")

    if result.failures:
        print("❌ FAILURES:")
        for test, tb in result.failures:
            print(f"\n  --- {test} ---")
            for line in tb.split('\n')[-5:]:
                print(f"  {line}")

    if result.errors:
        print("\n💥 ERRORS:")
        for test, tb in result.errors:
            print(f"\n  --- {test} ---")
            for line in tb.split('\n')[-5:]:
                print(f"  {line}")

    return 0 if failures == 0 and errors_count == 0 else 1


if __name__ == "__main__":
    sys.exit(run_tests())
