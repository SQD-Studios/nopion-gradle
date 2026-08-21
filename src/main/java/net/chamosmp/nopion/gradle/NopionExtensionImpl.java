/*
 * Copyright 2024 PaperMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.chamosmp.nopion.gradle;

import javax.inject.Inject;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ProviderFactory;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class NopionExtensionImpl implements NopionExtension {
  private final Property<String> apiUrl;
  private final Property<String> apiToken;
  private final Property<String> project;
  private final Property<String> family;
  private final Property<String> version;
  private final Property<String> buildTimestamp;
  private final Build build;

  @Inject
  public NopionExtensionImpl(final ObjectFactory objects, final ProviderFactory providers) {
    this.apiUrl = objects.property(String.class).convention(providers.environmentVariable("NOPION_API_URL"));
    this.apiToken = objects.property(String.class).convention(providers.environmentVariable("NOPION_API_KEY"));
    this.project = objects.property(String.class);
    this.family = objects.property(String.class);
    this.version = objects.property(String.class);
    this.build = objects.newInstance(BuildImpl.class);
    this.buildTimestamp = objects.property(String.class)
      .convention(providers.environmentVariable("BUILD_STARTED_AT")
        .orElse(providers.gradleProperty("BUILD_STARTED_AT")));
  }

  @Override
  public Property<String> getApiUrl() {
    return this.apiUrl;
  }

  @Override
  public Property<String> getApiToken() {
    return this.apiToken;
  }

  @Override
  public Property<String> getProject() {
    return this.project;
  }

  @Override
  public Property<String> getVersionFamily() {
    return this.family;
  }

  @Override
  public Property<String> getVersion() {
    return this.version;
  }

  @Override
  public Build getBuild() {
    return this.build;
  }

  @Override
  public Property<String> getBuildTimestamp() {
    return this.buildTimestamp;
  }

  @NullMarked
  public static class BuildImpl implements Build {
    private final Property<String> id;
    private final NamedDomainObjectContainer<Download> downloads;

    @Inject
    public BuildImpl(final ObjectFactory objects, final ProviderFactory providers) {
      this.id = objects.property(String.class).convention(providers.environmentVariable("BUILD_NUMBER"));
      this.downloads = objects.domainObjectContainer(Download.class);
    }

    @Override
    public Property<String> getId() {
      return this.id;
    }

    @Override
    public NamedDomainObjectContainer<Download> getDownloads() {
      return this.downloads;
    }
  }
}
