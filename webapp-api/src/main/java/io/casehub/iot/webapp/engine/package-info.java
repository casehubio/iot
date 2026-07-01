/**
 * Case descriptors for IoT automation workflows.
 *
 * <p>Each descriptor is a plain POJO carrying worker lambdas, capability routing,
 * and SLA policies for one case type. Constructed by the companion YamlCaseHub
 * subclass with CDI-managed dependencies. Testable without Quarkus — pass
 * {@code null} for dependencies when testing structure only.
 */
package io.casehub.iot.webapp.engine;
