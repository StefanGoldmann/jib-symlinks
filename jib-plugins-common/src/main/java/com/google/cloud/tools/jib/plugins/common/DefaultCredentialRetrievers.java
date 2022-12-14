/*
 * Copyright 2018 Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.plugins.common;

import com.google.cloud.tools.jib.api.Credential;
import com.google.cloud.tools.jib.api.CredentialRetriever;
import com.google.cloud.tools.jib.frontend.CredentialRetrieverFactory;
import com.google.common.annotations.VisibleForTesting;
import java.io.FileNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Generates a list of default {@link CredentialRetriever}s.
 *
 * <p>The retrievers are, in order of first-checked to last-checked:
 *
 * <ol>
 *   <li>{@link CredentialRetrieverFactory#known} for known credential, if set
 *   <li>{@link CredentialRetrieverFactory#dockerCredentialHelper} for a known credential helper, if
 *       set
 *   <li>{@link CredentialRetrieverFactory#dockerConfig} for {@code
 *       $XDG_RUNTIME_DIR/containers/auth.json},
 *   <li>{@link CredentialRetrieverFactory#dockerConfig} for {@code
 *       $XDG_CONFIG_HOME/containers/auth.json},
 *   <li>{@link CredentialRetrieverFactory#dockerConfig} for {@code
 *       $HOME/.config/containers/auth.json},
 *   <li>{@link CredentialRetrieverFactory#known} for known inferred credential, if set
 *   <li>{@link CredentialRetrieverFactory#dockerConfig} for {@code $DOCKER_CONFIG/config.json},
 *       {@code $DOCKER_CONFIG/.dockerconfigjson}, {@code $DOCKER_CONFIG/.dockercfg},
 *       System.get("user.home")/.docker/config.json}, {@code
 *       System.get("user.home")/.docker/.dockerconfigjson}, {@code
 *       System.get("user.home")/.docker/.dockercfg}, {@code $HOME/.docker/config.json}, {@code
 *       $HOME/.docker/.dockerconfigjson}, and {@code $HOME/.docker/.dockercfg}
 *   <li>{@link CredentialRetrieverFactory#wellKnownCredentialHelpers} for well-known credential
 *       helper-registry pairs
 *   <li>{@link CredentialRetrieverFactory#googleApplicationDefaultCredentials} for GCR registry
 * </ol>
 */
public class DefaultCredentialRetrievers {

  /**
   * See <a
   * href="https://docs.docker.com/engine/reference/commandline/login/#privileged-user-requirement">https://docs.docker.com/engine/reference/commandline/login/#privileged-user-requirement</a>.
   */
  private static final Path DOCKER_CONFIG_FILE = Paths.get("config.json");
  // for Kubernetes: https://github.com/GoogleContainerTools/jib/issues/2260
  private static final Path KUBERNETES_DOCKER_CONFIG_FILE = Paths.get(".dockerconfigjson");
  private static final Path LEGACY_DOCKER_CONFIG_FILE = Paths.get(".dockercfg");
  // for Podman: https://www.mankier.com/5/containers-auth.json
  private static final Path XDG_AUTH_FILE = Paths.get("containers").resolve("auth.json");

  /**
   * Creates a new {@link DefaultCredentialRetrievers} with a given {@link
   * CredentialRetrieverFactory}.
   *
   * @param credentialRetrieverFactory the {@link CredentialRetrieverFactory} to generate the {@link
   *     CredentialRetriever}s
   * @return a new {@link DefaultCredentialRetrievers}
   */
  public static DefaultCredentialRetrievers init(
      CredentialRetrieverFactory credentialRetrieverFactory) {
    return new DefaultCredentialRetrievers(
        credentialRetrieverFactory, System.getProperties(), System.getenv());
  }

  private final CredentialRetrieverFactory credentialRetrieverFactory;

  @Nullable private CredentialRetriever knownCredentialRetriever;
  @Nullable private CredentialRetriever inferredCredentialRetriever;
  @Nullable private String credentialHelper;
  private final Properties systemProperties;
  private final Map<String, String> environment;

  @VisibleForTesting
  DefaultCredentialRetrievers(
      CredentialRetrieverFactory credentialRetrieverFactory,
      Properties systemProperties,
      Map<String, String> environment) {
    this.credentialRetrieverFactory = credentialRetrieverFactory;
    this.systemProperties = systemProperties;
    this.environment = environment;
  }

  /**
   * Sets the known {@link Credential} to use in the default credential retrievers.
   *
   * @param knownCredential the known credential
   * @param credentialSource the source of the known credential (for logging)
   * @return this
   */
  public DefaultCredentialRetrievers setKnownCredential(
      Credential knownCredential, String credentialSource) {
    knownCredentialRetriever = credentialRetrieverFactory.known(knownCredential, credentialSource);
    return this;
  }

  /**
   * Sets the inferred {@link Credential} to use in the default credential retrievers.
   *
   * @param inferredCredential the inferred credential
   * @param credentialSource the source of the inferred credential (for logging)
   * @return this
   */
  public DefaultCredentialRetrievers setInferredCredential(
      Credential inferredCredential, String credentialSource) {
    inferredCredentialRetriever =
        credentialRetrieverFactory.known(inferredCredential, credentialSource);
    return this;
  }

  /**
   * Sets the known credential helper. May either be a path to a credential helper executable, or a
   * credential helper suffix (following {@code docker-credential-}).
   *
   * @param credentialHelper the path to a credential helper, or a credential helper suffix
   *     (following {@code docker-credential-}).
   * @return this
   */
  public DefaultCredentialRetrievers setCredentialHelper(@Nullable String credentialHelper) {
    this.credentialHelper = credentialHelper;
    return this;
  }

  /**
   * Makes a list of {@link CredentialRetriever}s.
   *
   * @return the list of {@link CredentialRetriever}s
   * @throws FileNotFoundException if a credential helper path is specified, but the file doesn't
   *     exist
   */
  public List<CredentialRetriever> asList() throws FileNotFoundException {
    List<CredentialRetriever> credentialRetrievers = new ArrayList<>();
    if (knownCredentialRetriever != null) {
      credentialRetrievers.add(knownCredentialRetriever);
    }
    if (credentialHelper != null) {
      // If credential helper contains file separator, treat as path; otherwise treat as suffix
      if (credentialHelper.contains(FileSystems.getDefault().getSeparator())) {
        if (!Files.exists(Paths.get(credentialHelper))) {
          String osName = systemProperties.getProperty("os.name").toLowerCase(Locale.ENGLISH);
          if (!osName.contains("windows")
              || (!Files.exists(Paths.get(credentialHelper + ".cmd"))
                  && !Files.exists(Paths.get(credentialHelper + ".exe")))) {
            throw new FileNotFoundException(
                "Specified credential helper was not found: " + credentialHelper);
          }
        }
        credentialRetrievers.add(
            credentialRetrieverFactory.dockerCredentialHelper(credentialHelper));
      } else {
        String suffix = credentialHelper; // not path; treat as suffix
        credentialRetrievers.add(
            credentialRetrieverFactory.dockerCredentialHelper("docker-credential-" + suffix));
      }
    }

    if (inferredCredentialRetriever != null) {
      credentialRetrievers.add(inferredCredentialRetriever);
    }

    Set<Path> dockerConfigFiles = new LinkedHashSet<>();

    String xdgRuntime = environment.get("XDG_RUNTIME_DIR");
    if (xdgRuntime != null) {
      dockerConfigFiles.add(Paths.get(xdgRuntime).resolve(XDG_AUTH_FILE));
    }
    String xdgConfigHome = environment.get("XDG_CONFIG_HOME");
    if (xdgConfigHome != null) {
      dockerConfigFiles.add(Paths.get(xdgConfigHome).resolve(XDG_AUTH_FILE));
    }
    String homeProperty = systemProperties.getProperty("user.home");
    if (homeProperty != null) {
      dockerConfigFiles.add(Paths.get(homeProperty).resolve(".config").resolve(XDG_AUTH_FILE));
    }
    String homeEnvVar = environment.get("HOME");
    if (homeEnvVar != null) {
      dockerConfigFiles.add(Paths.get(homeEnvVar).resolve(".config").resolve(XDG_AUTH_FILE));
    }

    String dockerConfigEnv = environment.get("DOCKER_CONFIG");
    if (dockerConfigEnv != null) {
      dockerConfigFiles.addAll(getDockerFiles(Paths.get(dockerConfigEnv)));
    }
    if (homeProperty != null) {
      dockerConfigFiles.addAll(getDockerFiles(Paths.get(homeProperty).resolve(".docker")));
    }
    if (homeEnvVar != null) {
      dockerConfigFiles.addAll(getDockerFiles(Paths.get(homeEnvVar).resolve(".docker")));
    }

    dockerConfigFiles.stream()
        .map(
            path ->
                path.endsWith(LEGACY_DOCKER_CONFIG_FILE)
                    ? credentialRetrieverFactory.legacyDockerConfig(path)
                    : credentialRetrieverFactory.dockerConfig(path))
        .forEach(credentialRetrievers::add);

    credentialRetrievers.add(credentialRetrieverFactory.wellKnownCredentialHelpers());
    credentialRetrievers.add(credentialRetrieverFactory.googleApplicationDefaultCredentials());
    return credentialRetrievers;
  }

  private List<Path> getDockerFiles(Path configDir) {
    return Arrays.asList(
        configDir.resolve(DOCKER_CONFIG_FILE),
        configDir.resolve(KUBERNETES_DOCKER_CONFIG_FILE),
        configDir.resolve(LEGACY_DOCKER_CONFIG_FILE));
  }
}
