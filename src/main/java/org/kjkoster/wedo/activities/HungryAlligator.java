package org.kjkoster.wedo.activities;

import static java.lang.Thread.sleep;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.IOException;
import java.util.Collection;

import org.kjkoster.wedo.bricks.Distance;
import org.kjkoster.wedo.bricks.WeDoBricks;
import org.kjkoster.wedo.usb.Usb;

/**
 * LEGO WeDo's hungry alligator.
 * 
 * @author Kees Jan Koster &lt;kjkoster@kjkoster.org&gt;
 */
public class HungryAlligator {
    private static WeDoBricks weDoBricks = null;

    /**
     * The main entry point.
     * 
     * @param args
     *            Ignored.
     * @throws IOException
     *             When there was a problem talking to USB.
     */
    public static void main(final String[] args) throws IOException {
        try (final Usb usb = new Usb(false)) {
            weDoBricks = new WeDoBricks(usb, true);
            weDoBricks.reset();

            for (;;) {
                openJawSlowly();
                waitForBait();
                slamShut();
            }
        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            // It seems that under Mac OS X a thread is still stuck in the
            // hidapi USB library, so we force the JVM to exit.
            System.exit(0);
        }
    }

    private static void openJawSlowly() throws IOException,
            InterruptedException {
        weDoBricks.motor((byte) 30);
        sleep(SECONDS.toMillis(3L));
        weDoBricks.motor((byte) 0x00);
    }

    private static void waitForBait() throws IOException, InterruptedException {
        for (;;) {
            sleep(MILLISECONDS.toMillis(100L));

            final Collection<Distance> distances = weDoBricks.distances();
            if (distances.size() > 0 && distances.iterator().next().getCm() < 1) {
                return;
            }
        }
    }

    private static void slamShut() throws IOException, InterruptedException {
        weDoBricks.motor((byte) -127);
        sleep(MILLISECONDS.toMillis(400L));
        weDoBricks.motor((byte) 0x00);
        sleep(MILLISECONDS.toMillis(400L));
    }
}
