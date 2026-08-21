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
package net.chamosmp.nopion.gradle.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Consumer;
import javax.inject.Inject;
import net.chamosmp.nopion.gradle.NopionExtension;
import net.chamosmp.nopion.model.Commit;
import net.chamosmp.nopion.model.Result;
import net.chamosmp.nopion.model.request.v2.CreateBuildRequest;
import net.chamosmp.nopion.model.response.v2.BuildResponse;
import net.chamosmp.nopion.model.response.v2.CreateBuildResponse;
import net.chamosmp.nopion.model.response.v2.ProjectResponse;
import net.chamosmp.nopion.model.response.v2.VersionResponse;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.jetbrains.annotations.VisibleForTesting;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
@UntrackedTask(because = "PublishToNopionTask should always run when requested")
public abstract class PublishToNopionTask extends DefaultTask implements AutoCloseable {
  public static final String NAME = "publishToNopion";
  private static final String APPLICATION_JAVA_ARCHIVE = "application/java-archive";
  private static final String USER_AGENT = "Nopion (Gradle Plugin)";
  private final HttpClient httpClient = HttpClient.newBuilder()
    .build();

  public PublishToNopionTask() {
    this.setGroup("nopion");
    this.setDescription("Publish to Nopion");
  }

  @Nested
  public abstract Property<NopionExtension> getExtension();

  @Inject
  public abstract ProjectLayout getProjectLayout();

  private void withGit(final Consumer<Git> consumer) {
    final File settingsDir = this.getProjectLayout().getSettingsDirectory().getAsFile();
    try (final Git git = Git.open(settingsDir)) {
      consumer.accept(git);
    } catch (final IOException e) {
      throw new GradleException("Failed to open git repository", e);
    }
  }

  @TaskAction
  public void run() {
    this.withGit(this::runWithGit);
  }

  private void runWithGit(final Git git) {
    final NopionExtension extension = this.getExtension().get();

    final String project = extension.getProject().get();
    final String versionId = extension.getVersion().get();
    final NopionExtension.Build build = extension.getBuild();
    final String buildId = build.getId().get();
    final String timeString = this.getExtension().get().getBuildTimestamp().getOrNull();
    final Instant time;
    if (timeString != null) {
      try {
        time = Instant.parse(timeString);
      } catch (final DateTimeParseException e) {
        throw new GradleException("Failed to parse build timestamp: " + timeString, e);
      }
    } else {
      time = Instant.now();
    }

    final List<Commit> commits = this.gatherCommits(git, extension);

    if (!extension.getApiToken().isPresent()) {
      throw new GradleException("API token is not present");
    }
    final String apiToken = extension.getApiToken().get();
    try {
      if (build.getDownloads().isEmpty()) {
        throw new GradleException("No downloads to publish");
      }

      final NopionExtension.Download download = build.getDownloads().iterator().next();
      final Path path = download.getFile().get().getAsFile().toPath();
      final byte[] content = Files.readAllBytes(path);
      final String fileName = path.getFileName().toString();
      final String fileExtension = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf(".") + 1) : "";

      final CreateBuildRequest request = new CreateBuildRequest(
        project,
        versionId,
        buildId,
        Result.SUCCESS,
        time.toEpochMilli(),
        0,
        commits.reversed(),
        Map.of(),
        fileExtension
      );
      final CreateBuildResponse response = this.createBuild(extension, apiToken, request);
      this.upload(extension, apiToken, response.stateKey(), content);
    } catch (final JsonProcessingException e) {
      throw new GradleException("Failed to serialize JSON", e);
    } catch (final IOException e) {
      throw new GradleException("Failed to publish files", e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GradleException("Publishing was interrupted", e);
    }
  }

  private static String apiUrl(final NopionExtension extension) {
    final String url = extension.getApiUrl().get();
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  private CreateBuildResponse createBuild(
    final NopionExtension extension,
    final String apiToken,
    final CreateBuildRequest request
  ) throws IOException, InterruptedException {
    final HttpRequest httpRequest = HttpRequest.newBuilder()
      .uri(URI.create(apiUrl(extension) + "/v2/create"))
      .header("Authorization", apiToken)
      .header("Content-Type", "application/json")
      .header("User-Agent", USER_AGENT)
      .POST(HttpRequest.BodyPublishers.ofString(MapperHolder.MAPPER.writeValueAsString(request)))
      .build();
    final HttpResponse<String> response = this.httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("Failed to create build: " + response.statusCode() + ": " + response.body());
    }
    return MapperHolder.MAPPER.readValue(response.body(), CreateBuildResponse.class);
  }

  private void upload(final NopionExtension extension, final String apiToken, final String stateKey, final byte[] content) throws IOException, InterruptedException {
    final HttpRequest request = HttpRequest.newBuilder()
      .uri(URI.create(apiUrl(extension) + "/v2/create/upload?stateKey=" + stateKey))
      .header("Authorization", apiToken)
      .header("Content-Type", APPLICATION_JAVA_ARCHIVE)
      .header("User-Agent", USER_AGENT)
      .POST(HttpRequest.BodyPublishers.ofByteArray(content))
      .build();
    final HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) {
      throw new IOException("Failed to upload file: " + response.statusCode() + ": " + response.body());
    }
  }

  private static String contentMd5(final byte[] content) {
    try {
      return Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(content));
    } catch (final NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private List<Commit> gatherCommits(Git git, NopionExtension extension) {
    final List<Commit> commits = new ArrayList<>();
    try (final RevWalk revWalk = new RevWalk(git.getRepository())) {
      final RevCommit currentCommit = revWalk.parseCommit(git.getRepository().exactRef(Constants.HEAD).getObjectId());
      revWalk.markStart(currentCommit);

      final List<BuildResponse> builds = this.fetchPreviousBuilds(extension);
      if (builds != null && !builds.isEmpty()) {
        // not every build might have commits, we have to find the last one that did have some
        BuildResponse lastBuildWithCommits = null;
        for (final BuildResponse build : builds) {
          if (!build.commits().isEmpty()) {
            lastBuildWithCommits = build;
            break;
          }
        }

        if (lastBuildWithCommits != null) {
          final Commit lastCommit = lastBuildWithCommits.commits().getFirst();
          if (!this.tryMarkPreviousBuildCommit(git, revWalk, currentCommit, lastCommit)) {
            return commits;
          }
        }
      }

      for (final RevCommit commit : revWalk) {
        commits.add(new Commit(
          commit.getAuthorIdent().getName(),
          commit.getAuthorIdent().getEmailAddress(),
          commit.getFullMessage(),
          commit.getName(),
          commit.getAuthorIdent().getWhenAsInstant().toEpochMilli()
        ));
      }
    } catch (final IOException e) {
      throw new GradleException("Failed to get commit data", e);
    }
    return commits;
  }

  private boolean tryMarkPreviousBuildCommit(final Git git, final RevWalk revWalk, final RevCommit currentCommit, final Commit lastCommit) throws IOException {
    final ObjectId lastBuildObjectId = git.getRepository().resolve(lastCommit.hash());
    if (lastBuildObjectId == null) {
      this.logEmptyChangelogWarning(
        lastCommit.hash(),
        currentCommit.getName(),
        "previous build commit is not present in the local repository",
        false
      );
      return false;
    }

    try (final RevWalk ancestryWalk = new RevWalk(git.getRepository())) {
      final RevCommit previousBuildCommit = ancestryWalk.parseCommit(lastBuildObjectId);
      final RevCommit currentBuildCommit = ancestryWalk.parseCommit(currentCommit);
      if (!ancestryWalk.isMergedInto(previousBuildCommit, currentBuildCommit)) {
        this.logEmptyChangelogWarning(
          lastCommit.hash(),
          currentCommit.getName(),
          "previous build commit is not an ancestor of the current HEAD",
          false
        );
        return false;
      }
    } catch (final MissingObjectException e) {
      // looks like history was squashed away, lets just use this commit as changelog
      revWalk.markUninteresting(revWalk.parseCommit(currentCommit.getParent(0)));
      this.logEmptyChangelogWarning(
        lastCommit.hash(),
        currentCommit.getName(),
        "previous build commit could not be loaded from the local repository",
        true
      );
      return true;
    }

    revWalk.markUninteresting(revWalk.parseCommit(lastBuildObjectId));
    return true;
  }

  private void logEmptyChangelogWarning(final String previousCommit, final String currentCommit, final String reason, final boolean shortChangelog) {
    this.getLogger().warn(
      "Unable to compute changelog: {} (previous build commit: {}, current HEAD: {}). {}",
      reason,
      previousCommit,
      currentCommit,
      shortChangelog ? "Publishing with short changelog" : "Publishing with an empty changelog."
    );
  }

  /**
   * Try to get all the previous builds for this version and falls back to the previous version
   *
   * @param extension The extension instance
   * @return The previous builds as {@link BuildResponse}
   */
  private @Nullable List<BuildResponse> fetchPreviousBuilds(final NopionExtension extension) {
    final String currentVersion = extension.getVersion().get();
    final ProjectResponse projectData = this.getProjectData(extension);

    if (projectData == null) return null;

    // Check if the current version already has builds
    if (projectData.versions().contains(currentVersion)) {
      final List<BuildResponse> builds = this.fetchCurrentVersionBuilds(extension, currentVersion);
      if (!builds.isEmpty()) {
        return builds;
      }
    }

    // For new versions without builds, fall back to finding the last version with builds
    for (final String version : projectData.versions()) {
      final List<BuildResponse> builds = this.getBuilds(extension, version);
      if (!builds.isEmpty()) {
        return builds;
      }
    }
    return null;
  }

  private List<BuildResponse> fetchCurrentVersionBuilds(final NopionExtension extension, final String version) {
    return this.getBuilds(extension, version);
  }

  private @Nullable ProjectResponse getProjectData(final NopionExtension extension) {
    final String url = String.format(
      "%s/v2/%s",
      apiUrl(extension),
      extension.getProject().get()
    );
    try {
      final HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("User-Agent", USER_AGENT)
        .build();
      final HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      final int statusCode = response.statusCode();
      if (statusCode == 200) {
        return MapperHolder.MAPPER.readValue(response.body(), ProjectResponse.class);
      }
      return null;
    } catch (final IOException | InterruptedException e) {
      return null;
    }
  }

  private List<BuildResponse> getBuilds(final NopionExtension extension, final String version) {
    final String url = String.format(
      "%s/v2/%s/%s",
      apiUrl(extension),
      extension.getProject().get(),
      version
    );
    try {
      final HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("User-Agent", USER_AGENT)
        .build();
      final HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      final int statusCode = response.statusCode();
      if (statusCode == 200) {
        final VersionResponse versionResponse = MapperHolder.MAPPER.readValue(response.body(), VersionResponse.class);
        final List<BuildResponse> builds = new ArrayList<>();
        // Fetch only the latest 5 builds to avoid too many requests
        final List<String> buildIds = versionResponse.builds();
        final int toFetch = Math.min(buildIds.size(), 5);
        for (int i = 0; i < toFetch; i++) {
          final BuildResponse build = this.getBuild(extension, version, buildIds.get(buildIds.size() - 1 - i));
          if (build != null) {
            builds.add(build);
          }
        }
        return builds;
      }
      return Collections.emptyList();
    } catch (final IOException | InterruptedException e) {
      return Collections.emptyList();
    }
  }

  private @Nullable BuildResponse getBuild(final NopionExtension extension, final String version, final String buildId) {
    final String url = String.format(
      "%s/v2/%s/%s/%s",
      apiUrl(extension),
      extension.getProject().get(),
      version,
      buildId
    );
    try {
      final HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("User-Agent", USER_AGENT)
        .build();
      final HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        return MapperHolder.MAPPER.readValue(response.body(), BuildResponse.class);
      }
      return null;
    } catch (final IOException | InterruptedException e) {
      return null;
    }
  }

  @Override
  public void close() {
    this.httpClient.close();
  }

  @VisibleForTesting
  public static final class MapperHolder {
    public static final ObjectMapper MAPPER = new ObjectMapper()
      .registerModule(new JavaTimeModule());
  }
}
