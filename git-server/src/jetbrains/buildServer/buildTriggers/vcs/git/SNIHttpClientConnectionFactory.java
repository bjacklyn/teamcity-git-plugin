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

package jetbrains.buildServer.buildTriggers.vcs.git;

import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.Proxy;
import java.net.URL;
import java.security.KeyStore;
import java.util.function.Supplier;

public class SNIHttpClientConnectionFactory implements HttpConnectionFactory {

  private Supplier<KeyStore> myTrustStoreGetter;

  /**
   * @deprecated use {@link #SNIHttpClientConnectionFactory(Supplier)} instead.
   */
  @Deprecated
  public SNIHttpClientConnectionFactory() {
    this(() -> null);
  }

  public SNIHttpClientConnectionFactory(@NotNull Supplier<KeyStore> trustStoreGetter) {
    myTrustStoreGetter = trustStoreGetter;
  }

  public HttpConnection create(final URL url) throws IOException {
    SNIHttpClientConnection connection = new SNIHttpClientConnection(url.toString());
    connection.setTrustStoreGetter(myTrustStoreGetter);
    return connection;
  }

  public HttpConnection create(final URL url, final Proxy proxy) throws IOException {
    SNIHttpClientConnection connection = new SNIHttpClientConnection(url.toString(), proxy);
    connection.setTrustStoreGetter(myTrustStoreGetter);
    return connection;
  }
}
