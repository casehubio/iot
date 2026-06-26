package io.casehub.iot.openhab;

import org.jboss.logging.Logger;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto-discovery for OpenHAB instances via mDNS and SSDP fallback.
 *
 * <p>Discovery sequence:
 * <ol>
 *   <li>mDNS (60% of timeout): tries {@code _openhab-server-ssl._tcp.local.} (preferred)
 *       then {@code _openhab-server._tcp.local.}</li>
 *   <li>SSDP (40% of timeout): M-SEARCH on {@code 239.255.255.250:1900}</li>
 * </ol>
 *
 * <p>Used only when {@code casehub.iot.openhab.url} is not configured.
 */
class OpenHabDiscovery {

    private static final Logger LOG = Logger.getLogger(OpenHabDiscovery.class);
    private static final String SERVICE_TYPE_SSL = "_openhab-server-ssl._tcp.local.";
    private static final String SERVICE_TYPE = "_openhab-server._tcp.local.";
    private static final String SSDP_MULTICAST = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final Pattern LOCATION_PATTERN = Pattern.compile("LOCATION:\\s*(.+?)(?:\r\n|\n|$)", Pattern.CASE_INSENSITIVE);

    /**
     * Resolves the OpenHAB base URL via auto-discovery.
     *
     * @param timeoutSeconds total discovery timeout in seconds (60% mDNS, 40% SSDP)
     * @return base URL in the form {@code http://host:port} or {@code https://host:port}
     * @throws IllegalStateException if no instance is discovered within timeout
     */
    static String resolve(int timeoutSeconds) {
        int mdnsTimeoutMs = (int) (timeoutSeconds * 1000 * 0.6);
        int ssdpTimeoutMs = (int) (timeoutSeconds * 1000 * 0.4);

        // Try mDNS first (SSL variant preferred)
        String url = tryMdns(mdnsTimeoutMs);
        if (url != null) {
            return url;
        }

        // Fallback to SSDP
        url = trySsdp(ssdpTimeoutMs);
        if (url != null) {
            return url;
        }

        throw new IllegalStateException(
            "No OpenHAB instance found via mDNS (" + SERVICE_TYPE_SSL + ", " + SERVICE_TYPE
            + ") or SSDP within " + timeoutSeconds + "s. Set casehub.iot.openhab.url explicitly.");
    }

    private static String tryMdns(int timeoutMs) {
        try (JmDNS jmdns = JmDNS.create(InetAddress.getLocalHost())) {
            // Try SSL variant first
            ServiceInfo[] services = jmdns.list(SERVICE_TYPE_SSL, timeoutMs / 2);
            if (services.length > 0) {
                ServiceInfo info = services[0];
                LOG.infof("Found OpenHAB via mDNS (SSL): %s:%d", info.getHostAddresses()[0], info.getPort());
                return buildUrl(info.getHostAddresses()[0], info.getPort(), true);
            }

            // Fallback to non-SSL
            services = jmdns.list(SERVICE_TYPE, timeoutMs / 2);
            if (services.length > 0) {
                ServiceInfo info = services[0];
                LOG.infof("Found OpenHAB via mDNS: %s:%d", info.getHostAddresses()[0], info.getPort());
                return buildUrl(info.getHostAddresses()[0], info.getPort(), false);
            }

            LOG.debug("No OpenHAB found via mDNS");
            return null;
        } catch (Exception e) {
            LOG.warnf(e, "mDNS discovery failed: %s", e.getMessage());
            return null;
        }
    }

    private static String trySsdp(int timeoutMs) {
        String msearch = "M-SEARCH * HTTP/1.1\r\n"
            + "HOST: " + SSDP_MULTICAST + ":" + SSDP_PORT + "\r\n"
            + "MAN: \"ssdp:discover\"\r\n"
            + "MX: " + (timeoutMs / 1000) + "\r\n"
            + "ST: upnp:rootdevice\r\n"
            + "\r\n";

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);

            // Send M-SEARCH
            byte[] sendData = msearch.getBytes(StandardCharsets.UTF_8);
            InetAddress group = InetAddress.getByName(SSDP_MULTICAST);
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, group, SSDP_PORT);
            socket.send(sendPacket);

            // Listen for responses
            byte[] receiveData = new byte[8192];
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                try {
                    socket.receive(receivePacket);
                    String response = new String(receivePacket.getData(), 0, receivePacket.getLength(), StandardCharsets.UTF_8);
                    String location = parseSsdpLocation(response);
                    if (location != null) {
                        LOG.infof("Found OpenHAB via SSDP: %s", location);
                        return location;
                    }
                } catch (IOException e) {
                    // Timeout or error on this receive attempt
                    break;
                }
            }
            return null;
        } catch (IOException e) {
            LOG.debugf("SSDP discovery failed: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Parses the LOCATION header from an SSDP response and strips the path.
     *
     * @param response raw SSDP response
     * @return base URL (scheme://host:port) or null if LOCATION not found
     */
    static String parseSsdpLocation(String response) {
        Matcher matcher = LOCATION_PATTERN.matcher(response);
        if (matcher.find()) {
            String location = matcher.group(1).trim();
            // Strip path — keep only scheme://host:port
            int pathStart = location.indexOf('/', location.indexOf("://") + 3);
            if (pathStart > 0) {
                return location.substring(0, pathStart);
            }
            return location;
        }
        return null;
    }

    /**
     * Constructs the base URL from host, port, and SSL flag.
     *
     * @param host IP address or hostname
     * @param port port number
     * @param ssl whether to use HTTPS
     * @return base URL in the form {@code http://host:port} or {@code https://host:port}
     */
    static String buildUrl(String host, int port, boolean ssl) {
        String scheme = ssl ? "https" : "http";
        return scheme + "://" + host + ":" + port;
    }
}
