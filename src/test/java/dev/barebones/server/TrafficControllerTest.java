package dev.barebones.server;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

public final class TrafficControllerTest {
    private TrafficControllerTest() {
    }

    public static void main(String[] args) throws Exception {
        separatesProtocolRateLimits();
        boundsConcurrentWorkAndReleasesPermitsOnce();
        System.out.println("Traffic controller tests passed");
    }

    private static void separatesProtocolRateLimits() throws Exception {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1, 10, new AtomicLong()::get);
        TrafficController controller = new TrafficController(2, 2, limiter);
        InetAddress client = InetAddress.getLoopbackAddress();

        TrafficController.Permit httpsPermit = requireAllowed(controller.admitHttps(client));
        httpsPermit.close();
        TrafficController.Admission rejected = controller.admitHttps(client);
        require(!rejected.allowed(), "HTTPS rate limit was ignored");
        require(rejected.rejectionReason() == TrafficController.RejectionReason.RATE_LIMIT,
                "HTTPS rejection reason was unexpected");
        TrafficController.Permit udpPermit = requireAllowed(controller.admitUdp(client));
        udpPermit.close();
    }

    private static void boundsConcurrentWorkAndReleasesPermitsOnce() throws Exception {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10, 10, new AtomicLong()::get);
        TrafficController controller = new TrafficController(1, 1, limiter);
        InetAddress client = InetAddress.getLoopbackAddress();

        TrafficController.Permit first = requireAllowed(controller.admitHttps(client));
        TrafficController.Admission rejected = controller.admitHttps(client);
        require(!rejected.allowed(), "HTTPS concurrency limit was ignored");
        require(rejected.rejectionReason() == TrafficController.RejectionReason.CONCURRENCY_LIMIT,
                "concurrency rejection reason was unexpected");
        first.close();
        first.close();

        TrafficController.Permit second = requireAllowed(controller.admitHttps(client));
        require(!controller.admitHttps(client).allowed(), "permit was released more than once");
        second.close();
    }

    private static TrafficController.Permit requireAllowed(TrafficController.Admission admission) {
        require(admission.allowed(), "admission was unexpectedly rejected");
        return admission.permit();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
