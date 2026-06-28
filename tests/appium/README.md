# NetProbe Appium Test Suite

Automated UI testing for **NetProbe v2.44** Android app using Appium + Python.

## Prerequisites

- Appium server running (`appium &`)
- Android emulator running (`emulator-5554`)
- NetProbe v2.44 installed on emulator
- Python 3.11+ with `Appium-Python-Client`

## Quick Start

```bash
# Run all tests
./run.sh

# Quick smoke test (launch + navigation only)
./run.sh --quick

# Only PROBE tab tests
./run.sh --probe-only

# Only DRIVE tab tests
./run.sh --drive-only
```

## Test Coverage

| Test Suite | Tests | Description |
|---|---|---|
| `TestAppLaunch` | 3 | App launch, title bar, tab visibility |
| `TestAroundTab` | 2 | AROUND tab elements, REFRESH button |
| `TestProbeTab` | 4 | PROBE tab metrics, START/STOP probe |
| `TestMtuTab` | 2 | MTU tab elements, FIND MTU test |
| `TestMaxTab` | 2 | MAX tab metrics, START MAX TEST |
| `TestDriveTab` | 5 | VPN status, keypair, split tunneling |
| `TestTraceTab` | 2 | TRACE tab log files |
| `TestNavigation` | 2 | Tab switching, rapid navigation |

## Output

- **Screenshots**: `/tmp/netprobe_screenshots/`
- **JSON Report**: `/tmp/netprobe_reports/netprobe_<timestamp>.json`

## UI Element Map

### Tab Bar (6 tabs)
| Tab | Content-desc | Description |
|---|---|---|
| AROUND | `Around` | Nearby UDP probe results |
| PROBE | `Probe` | UDP latency/bandwidth probe |
| MTU | `MTU` | Path MTU discovery |
| MAX | `MAX` | Throughput test |
| DRIVE | `Drive` | VPN connection + split tunneling |
| TRACE | `Trace` | Log files (FLOW/LOGS sub-tabs) |

### Key Resource IDs
| Element | Resource ID |
|---|---|
| Target text | `com.telcoagent.udpclient:id/targetText` |
| Status text | `com.telcoagent.udpclient:id/statusText` |
| Start button | `com.telcoagent.udpclient:id/startButton` |
| RTT value | `com.telcoagent.udpclient:id/latencyValue` |
| Refresh button | `com.telcoagent.udpclient:id/aroundYouRefresh` |
| Connect button | `com.telcoagent.udpclient:id/connectButton` |
| Split tunnel toggle | `com.telcoagent.udpclient:id/splitTunnelToggle` |
| Client pubkey | `com.telcoagent.udpclient:id/clientPubKeyText` |

## Adding Tests

```python
class TestMyFeature(NetProbeTestBase):
    def test_my_scenario(self):
        self.tap_tab("PROBE")
        self.assert_text_visible("START PROBE")
        self.screenshot("my_test")
```
