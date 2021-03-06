// Copyright (C) 2015 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.git.gpg;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.git.ReceivePackInitializer;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.util.BouncyCastleUtil;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.transport.PreReceiveHookChain;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.SignedPushConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Random;

public class SignedPushModule extends AbstractModule {
  private static final Logger log =
      LoggerFactory.getLogger(SignedPushModule.class);

  public static boolean isEnabled(Config cfg) {
    return cfg.getBoolean("receive", null, "enableSignedPush", false);
  }

  @Override
  protected void configure() {
    if (BouncyCastleUtil.havePGP()) {
      DynamicSet.bind(binder(), ReceivePackInitializer.class)
          .to(Initializer.class);
    } else {
      log.info("BouncyCastle PGP not installed; signed push verification is"
          + " disabled");
    }
  }

  @Singleton
  private static class Initializer implements ReceivePackInitializer {
    private final SignedPushConfig signedPushConfig;
    private final SignedPushPreReceiveHook hook;
    private final ProjectCache projectCache;

    @Inject
    Initializer(@GerritServerConfig Config cfg,
        SignedPushPreReceiveHook hook,
        ProjectCache projectCache) {
      this.hook = hook;
      this.projectCache = projectCache;

      if (isEnabled(cfg)) {
        String seed = cfg.getString("receive", null, "certNonceSeed");
        if (Strings.isNullOrEmpty(seed)) {
          seed = randomString(64);
        }
        signedPushConfig = new SignedPushConfig();
        signedPushConfig.setCertNonceSeed(seed);
        signedPushConfig.setCertNonceSlopLimit(
            cfg.getInt("receive", null, "certNonceSlop", 5 * 60));
      } else {
        signedPushConfig = null;
      }
    }

    @Override
    public void init(Project.NameKey project, ReceivePack rp) {
      ProjectState ps = projectCache.get(project);
      if (!ps.isEnableSignedPush()) {
        rp.setSignedPushConfig(null);
        return;
      }
      if (signedPushConfig == null) {
        log.error("receive.enableSignedPush is true for project {} but"
            + " false in gerrit.config, so signed push verification is"
            + " disabled", project.get());
      }
      rp.setSignedPushConfig(signedPushConfig);
      rp.setPreReceiveHook(PreReceiveHookChain.newChain(Lists.newArrayList(
          hook, rp.getPreReceiveHook())));
    }
  }

  private static String randomString(int len) {
    Random random;
    try {
      random = SecureRandom.getInstance("SHA1PRNG");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    StringBuilder sb = new StringBuilder(len);
    for (int i = 0; i < len; i++) {
      sb.append((char) random.nextInt());
    }
    return sb.toString();
  }

}
