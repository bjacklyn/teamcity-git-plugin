/*
 * Copyright 2000-2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.buildTriggers.vcs.git.commitInfo;

import jetbrains.buildServer.buildTriggers.vcs.git.submodules.SubmodulesConfig;
import org.eclipse.jgit.lib.AnyObjectId;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Created 20.02.14 14:35
 *
 * @author Eugene Petrenko (eugene.petrenko@jetbrains.com)
 */
public interface SubInfo {
  @NotNull
  Map<String, AnyObjectId> getSubmoduleToPath();

  @NotNull
  SubmodulesConfig getConfig();
}
