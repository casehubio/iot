package io.casehub.iot.homeassistant;

import org.jboss.logging.Logger;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.net.InetAddress;

/**
 * mDNS-based auto-discovery for Home Assistant instances on the local network.
 *
 * <p>Discovers Home Assistant via {@code _home-assistant._tcp.local.} service type.
 * Used only when {@code casehub.iot.homeassistant.url} is not configured.
 */
class HomeAssistantDiscovery {

    private static final Logger LOG = Logger.getLogger(HomeAssistantDiscovery.class);
    private static final String SERVICE_TYPE = "_home-assistant._tcp.local.";

    /**
     * Resolves the Home Assistant base URL via mDNS discovery.
     *
     * @param timeoutSeconds discovery timeout in seconds
     * @return base URL in the form {@code http://host:port}
     * @throws IllegalStateException if no HA instance is discovered within timeout
     */
    static String resolve(int timeoutSeconds) {
        try (JmDNS jmdns = JmDNS.create(InetAddress.getLocalHost())) {
            ServiceInfo[] services = jmdns.list(SERVICE_TYPE, timeoutSeconds * 1000);
            if (services.length == 0) {
                throw new IllegalStateException(
                    "No Home Assistant instance found via mDNS (" + SERVICE_TYPE
                    + ") within " + timeoutSeconds + "s. Set casehub.iot.homeassistant.url explicitly.");
            }
            if (services.length > 1) {
                LOG.infof("Found %d HA instances via mDNS, using first: %s:%d",
                    services.length,
                    services[0].getHostAddresses()[0],
                    services[0].getPort());
            }
            ServiceInfo info = services[0];
            return buildUrl(info.getHostAddresses()[0], info.getPort());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("mDNS discovery failed: " + e.getMessage(), e);
        }
    }

    /**
     * Constructs the base URL from host and port.
     *
     * @param host IP address or hostname
     * @param port port number
     * @return base URL in the form {@code http://host:port}
     */
    static String buildUrl(String host, int port) {
        return "http://" + host + ":" + port;
    }
}
