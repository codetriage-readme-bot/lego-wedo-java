package org.kjkoster.wedo.systems.sbrick;

import static java.lang.Integer.parseInt;
import static java.lang.String.format;
import static java.lang.System.err;
import static java.lang.System.exit;
import static java.lang.System.out;
import static java.lang.Thread.sleep;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.kjkoster.wedo.bricks.Brick.FIRST_PORT;
import static org.kjkoster.wedo.bricks.Brick.Type.UNKNOWN;
import static org.kjkoster.wedo.transport.ble112.BLE112Connections.CONN_INTERVAL_MAX;
import static org.kjkoster.wedo.transport.ble112.BLE112Connections.CONN_INTERVAL_MIN;
import static org.kjkoster.wedo.transport.ble112.BLE112Connections.CONN_LATENCY;
import static org.kjkoster.wedo.transport.ble112.BLE112Connections.CONN_TIMEOUT;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Queue;

import org.kjkoster.wedo.bricks.Brick;
import org.kjkoster.wedo.bricks.Hub;
import org.kjkoster.wedo.transport.ble112.BLE112Address;
import org.thingml.bglib.BDAddr;
import org.thingml.bglib.BGAPI;
import org.thingml.bglib.BGAPIDefaultListener;

import lombok.SneakyThrows;

/**
 * A scanner that searches for SBrick and SBrick Plus BLE hubs. Scanning,
 * connecting and then interrogating the SBricks is rather involved due to the
 * asynchronous nature of BGAPI. We can only send commands when previous
 * commands gave a response. This class uses the responses to trigger further
 * actions. This gives a rather brittle process that is hard to read, but it
 * seems to work quite well for most circumstances.
 *
 * @author Kees Jan Koster &lt;kjkoster@kjkoster.org&gt;
 */
public class SBrickScanner extends BGAPIDefaultListener {
    /**
     * At what intervals is scanner started. This value is measured in 625 us
     * units and has a range from 20 ms to 10240 ms.
     */
    private static final int SCAN_INTERVAL = 10;

    /**
     * How long to scan at each interval, unit is 625us. Must be equal or
     * smaller than the value of the scan interval.
     */
    private static final int SCAN_WINDOW = 250;

    /**
     * Use active scanning (value 1) or passive scanning (value 0).
     */
    private static final int SCAN_ACTIVE = 1;

    private static final int HANDLE_VENDOR = 0x10;
    private static final int HANDLE_VERSION = 0x0a;
    private static final int HANDLE_NAME = 0x03;

    /**
     * The BGAPI interface.
     */
    private final BGAPI bgapi;

    /**
     * All peripherals that responded to a scan request.
     */
    private final Queue<BLE112Address> ble112Addresses = new LinkedList<>();

    /**
     * The address of the peripheral that we are currently connected to. Used
     * during the interrogation process to remember the address across messages.
     */
    private BLE112Address connectedAddress = null;

    /**
     * The firmware version of the peripheral that we are currently connected
     * to. Used during the interrogation process to remember the firmware
     * version across messages.
     */
    private String version = "";

    /**
     * The complete and supported SBricks that we found so far.
     */
    private final Collection<Hub> foundHubs = new ArrayList<>();

    /**
     * Start a new scanner to look for SBricks.
     * 
     * @param bgapi
     *            The BLE112 API to use.
     */
    public SBrickScanner(final BGAPI bgapi) {
        super();

        this.bgapi = bgapi;
        bgapi.addListener(this);
    }

    /**
     * Read a map of all the bricks. We scan for SBricks. Unfortunately SBricks
     * do not support detection of the bricks plugged into them, so we get an
     * empty list back. SBricks can be switched on and off at any time, so it is
     * a surprise how many bricks we get every time.
     * 
     * @return All the bricks, neatly laid out in a map.
     */
    @SneakyThrows
    public Collection<Hub> scan() {
        bgapi.send_system_get_info();

        try {
            sleep(SECONDS.toMillis(3L));
        } finally {
            // the response to <code>send_gap_end_procedure()</code> triggers
            // the connection and interrogation process. See
            // <code>receive_gap_end_procedure</code>, below.
            bgapi.send_gap_end_procedure();
        }

        // wait for the addresses to all be interrogated
        while (!ble112Addresses.isEmpty()) {
            sleep(MILLISECONDS.toMillis(500L));
        }
        sleep(SECONDS.toMillis(1L));

        return foundHubs;
    }

    /**
     * @see org.thingml.bglib.BGAPIDefaultListener#receive_system_get_info(int,
     *      int, int, int, int, int, int)
     */
    @Override
    public void receive_system_get_info(int major, int minor, int patch,
            int build, int ll_version, int protocol_version, int hw) {
        out.printf(
                "BLE112 found, version %d.%d.%d-%d, ll version: %d, protocol: %d, hardware: %d.\n",
                major, minor, patch, build, ll_version, protocol_version, hw);
        bgapi.send_system_get_connections();
    }

    /**
     * @see org.thingml.bglib.BGAPIDefaultListener#receive_system_get_connections(int)
     */
    @Override
    public void receive_system_get_connections(int maxconn) {
        out.printf(
                "This BLE112 device supports up to %d connections. Consult your device manual on how to increase that if you need more connections.\n\n",
                maxconn);
        bgapi.send_gap_set_scan_parameters(SCAN_INTERVAL, SCAN_WINDOW,
                SCAN_ACTIVE);
        bgapi.send_gap_discover(1 /* gap_discover_generic */);
    }

    private void connectNextAddress() {
        if (!ble112Addresses.isEmpty()) {
            final BLE112Address ble112Address = ble112Addresses.remove();
            connectedAddress = ble112Address;
            bgapi.send_gap_connect_direct(ble112Address.getBDAddr(),
                    ble112Address.getAddress_type(), CONN_INTERVAL_MIN,
                    CONN_INTERVAL_MAX, CONN_TIMEOUT, CONN_LATENCY);
        }
    }

    /**
     * Add the scan result to the candidate peripherals to interrogate later on.
     * We only add a peripheral if we don't already have it on the list. We will
     * receive multiple scan results for the same address.
     * 
     * @see org.thingml.bglib.BGAPIDefaultListener#receive_gap_scan_response(int,
     *      int, org.thingml.bglib.BDAddr, int, int, byte[])
     */
    @Override
    public void receive_gap_scan_response(final int rssi, final int packet_type,
            final BDAddr sender, final int address_type, final int bond,
            final byte[] data) {
        final BLE112Address ble112Address = new BLE112Address(sender,
                address_type);
        if (!ble112Addresses.contains(ble112Address)) {
            ble112Addresses.add(ble112Address);
        }
    }

    /**
     * @see org.thingml.bglib.BGAPIDefaultListener#receive_gap_end_procedure(int)
     */
    @Override
    public void receive_gap_end_procedure(final int result) {
        connectNextAddress();
    }

    /**
     * @see org.thingml.bglib.BGAPIDefaultListener#receive_connection_status(int,
     *      int, org.thingml.bglib.BDAddr, int, int, int, int, int)
     */
    @Override
    public void receive_connection_status(final int connection, final int flags,
            final BDAddr address, final int address_type,
            final int conn_interval, final int timeout, final int latency,
            final int bonding) {
        if (flags != 0x00) {
            // connected, kick off the interrogation
            bgapi.send_attclient_read_by_handle(connection, HANDLE_VENDOR);
        } else {
            // disconnected, so move to the next item
            connectNextAddress();
        }
    }

    /**
     * @see org.thingml.bglib.BGAPIDefaultListener#receive_connection_disconnected(int,
     *      int)
     */
    @Override
    public void receive_connection_disconnected(final int connection,
            final int reason) {
        connectNextAddress();
    }

    /**
     * @see org.thingml.bglib.BGAPIDefaultListener#receive_attclient_attribute_value(int,
     *      int, int, byte[])
     */
    @Override
    public void receive_attclient_attribute_value(final int connection,
            final int atthandle, final int type, final byte[] value) {
        switch (atthandle) {
        case HANDLE_VENDOR:
            if (!"Vengit Ltd.".equals(new String(value))) {
                // not an SBrick
                bgapi.send_connection_disconnect(connection);
            }
            bgapi.send_attclient_read_by_handle(connection, HANDLE_VERSION);
            break;

        case HANDLE_VERSION:
            version = new String(value);
            final int major = parseInt(version.split("\\.")[0]);
            final int minor = parseInt(version.split("\\.")[1]);
            if (major <= 4 && minor <= 2) {
                // pre-4.3 firmwares are not supported, ignore it until it has a
                // newer firmware
                out.printf(
                        "Found an SBrick that has an older, unsupported firmware version. Use the\n"
                                + "official SBrick app to update its firmware first and then re-run \"wedo -list\".\n\n");
                bgapi.send_connection_disconnect(connection);
            }
            bgapi.send_attclient_read_by_handle(connection, HANDLE_NAME);
            break;

        case HANDLE_NAME:
            final Brick[] bricks = new Brick[4];
            for (int i = 0; i < 4; i++) {
                bricks[i] = new Brick((char) (FIRST_PORT + i), UNKNOWN);
            }
            foundHubs.add(new Hub(connectedAddress.toString(),
                    format("%s, V%s", new String(value), version), bricks));
            bgapi.send_connection_disconnect(connection);
            break;

        default:
            bgapi.send_connection_disconnect(connection);
        }
    }

    /**
     * This callback is called when a peripheral is not an SBrick and it gives
     * an error when we query a handle it does not support.
     * 
     * @see org.thingml.bglib.BGAPIDefaultListener#receive_attclient_procedure_completed(int,
     *      int, int)
     */
    @Override
    public void receive_attclient_procedure_completed(final int connection,
            final int result, final int chrhandle) {
        bgapi.send_connection_disconnect(connection);
    }

    /**
     * We get this one occasionally when the program was interrupted at halfway
     * an operation.
     * 
     * @see org.thingml.bglib.BGAPIDefaultListener#receive_hardware_adc_read(int)
     */
    @Override
    @SneakyThrows
    public void receive_hardware_adc_read(final int result) {
        bgapi.send_system_reset(0);
        sleep(SECONDS.toMillis(1L));
        err.printf("BLE112 device reported error 0x%04x.\n", result);
        exit(1);
    }
}
