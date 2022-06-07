/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.prospero.galleon;

import org.apache.commons.io.FileUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.deployment.DeployRequest;
import org.eclipse.aether.deployment.DeploymentException;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.wildfly.prospero.api.exceptions.OperationException;
import org.wildfly.prospero.wfchannel.MavenSessionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LocalMavenCacheManager {
    private final Path base;

    public LocalMavenCacheManager(Path base) {
        this.base = base;
    }

    public Path rebuildRepo() throws OperationException {
        final List<String> lines;
        try {
            lines = Files.readAllLines(base.resolve("cache.properties"));
        } catch (IOException e) {
            throw new OperationException("Unable to read cached file", e);
        }

        final Path tempRepository;
        final RepositorySystem system;
        final DefaultRepositorySystemSession session;
        try {
            tempRepository = Files.createTempDirectory("prospero-rebuild-repo");
            final MavenSessionManager sessionManager = new MavenSessionManager(tempRepository);
            system = sessionManager.newRepositorySystem();
            session = sessionManager.newRepositorySystemSession(system, false);

            FileUtils.copyDirectory(base.resolve("cache").toFile(), tempRepository.toFile());
        } catch (IOException e) {
            throw new OperationException("Failed to init rebuild repository", e);
        }

        final DeployRequest deployRequest = new DeployRequest();
        deployRequest.setRepository(new RemoteRepository.Builder("cache", "default", tempRepository.toUri().toString()).build());
        for (String line : lines) {
            final String[] split = line.split(":");
            final DefaultArtifact gav = new DefaultArtifact(split[0], split[1], split[2], split[3], split[4], null, base.getParent().resolve(split[5]).toFile());

            deployRequest.addArtifact(gav);
        }

        try {
            system.deploy(session, deployRequest);
        } catch (DeploymentException e) {
            throw new OperationException("Unable to deploy artifacts to rebuild repository", e);
        }

        return tempRepository;
    }

    public void generateCacheRepository(Path provisionRepoPath, Set<String> resolvedArtifacts) throws OperationException {
        final MavenSessionManager sessionManager = new MavenSessionManager(provisionRepoPath);
        final RepositorySystem system = sessionManager.newRepositorySystem();
        final DefaultRepositorySystemSession session = sessionManager.newRepositorySystemSession(system, false);

        final HashSet<String> localArtifacts = loadInstalledArtifacts();

        final Set<String> missingArtifacts = new HashSet<>(resolvedArtifacts);
        missingArtifacts.removeAll(localArtifacts);

        final List<Artifact> cachedArtifacts = resolveCachedArtifacts(system, session, missingArtifacts);

        deployCachedArtifacts(system, session, cachedArtifacts);
    }

    private void deployCachedArtifacts(RepositorySystem system, DefaultRepositorySystemSession session, List<Artifact> cachedArtifacts)
            throws OperationException {
        try {
            final DeployRequest deployRequest = new DeployRequest();
            deployRequest.setArtifacts(cachedArtifacts);
            deployRequest.setRepository(new RemoteRepository.Builder("cache", "default", base.resolve("cache").toUri().toString()).build());
            system.deploy(session, deployRequest);
        } catch (DeploymentException e) {
            throw new OperationException("Unable to cache build artifacts", e);
        }
    }

    private List<Artifact> resolveCachedArtifacts(RepositorySystem system, DefaultRepositorySystemSession session, Set<String> missingArtifacts) {
        List<Artifact> cachedArtifacts = new ArrayList<>();
        for (String resolvedArtifact : missingArtifacts) {

            String[] split = resolvedArtifact.split(":");
            final DefaultArtifact gav = new DefaultArtifact(split[0], split[1], split[2], split[3], split[4]);


            final ArtifactRequest request = new ArtifactRequest();
            request.setArtifact(gav);
            final ArtifactResult artifactResult;
            try {
                artifactResult = system.resolveArtifact(session, request);

                cachedArtifacts.add(artifactResult.getArtifact());

            } catch (ArtifactResolutionException e) {
                e.printStackTrace();
            }
        }
        return cachedArtifacts;
    }

    private HashSet<String> loadInstalledArtifacts() throws OperationException {
        try {
            HashSet<String> localArtifacts = new HashSet<>();
            for (String line : Files.readAllLines(base.resolve("cache.properties"))) {
                final String key = line.substring(0, line.lastIndexOf(':'));
                localArtifacts.add(key);
            }
            return localArtifacts;
        } catch (IOException e) {
            throw new OperationException("Unable to read BOM of installed server", e);
        }
    }
}
