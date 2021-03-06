package appdash;

import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod;
import appdash.backend.App;
import appdash.web.*;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.server.handlers.form.EagerFormParsingHandler;
import io.undertow.server.session.InMemorySessionManager;
import io.undertow.server.session.SessionAttachmentHandler;
import io.undertow.server.session.SessionCookieConfig;
import io.undertow.util.Headers;
import org.pac4j.core.authorization.authorizer.RequireAnyRoleAuthorizer;
import org.pac4j.core.client.Clients;
import org.pac4j.core.config.Config;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.oidc.client.OidcClient;
import org.pac4j.oidc.config.OidcConfiguration;
import org.pac4j.oidc.profile.OidcProfile;
import org.pac4j.undertow.handler.CallbackHandler;
import org.pac4j.undertow.handler.LogoutHandler;
import org.pac4j.undertow.handler.SecurityHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

public class AppDash {
    private final int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    private final String baseUrl = System.getenv().getOrDefault("BASE_URL", "http://localhost:" + port)
            .replaceFirst("/+$", "");

    private List<String> listApps() throws IOException {
        return Files.list(Paths.get("/etc/jvmctl/apps"))
                .map(p -> p.getFileName().toString())
                .filter(s -> s.endsWith(".conf"))
                .map(s -> s.substring(0, s.length() - ".conf".length()))
                .sorted()
                .collect(Collectors.toList());
    }

    private void run() {
        Config authConfig = initAuthConfig();

        Router router = new Router("appdash/static");
        router.resources("/webjars", "META-INF/resources/webjars");
        router.GET("/", this::index);
        router.GET("/apps/{app}", this::overview);
        router.GET("/apps/{app}/config", this::config);
        router.POST("/apps/{app}/config", this::saveConfig);
        router.POST("/apps/{app}/restart", this::restart);
        router.POST("/apps/{app}/stop", this::stop);
        router.GET("/apps/{app}/deploy", this::showDeploy);
        router.POST("/apps/{app}/deploy", this::performDeploy);
        router.GET("/apps/{app}/logs", this::logs);
        router.GET("/apps/{app}/logs/stdio.log", request -> sendLog(request, "stdio.log"));
        router.GET("/apps/{app}/logs/deploy.log", request -> sendLog(request, "deploy.log"));

        HttpHandler handler = new BlockingHandler(router);
        handler = new EagerFormParsingHandler(handler);
        handler = SecurityHandler.build(handler, authConfig, "OidcClient", "developer");
        handler = Handlers.path(handler)
                .addExactPath("/logout", new LogoutHandler(authConfig))
                .addExactPath("/callback", CallbackHandler.build(authConfig));
        handler = new SessionAttachmentHandler(handler,
                new InMemorySessionManager("appdash"),
                new SessionCookieConfig()
                        .setHttpOnly(true)
                        .setCookieName("appdash-session"));
        handler = new ErrorHandler(handler);

        Undertow.builder()
                .addHttpListener(port, "0.0.0.0")
                .setHandler(handler)
                .build()
                .start();
    }

    private Response performDeploy(Request request) throws NotFoundException, IOException {
        App app = App.get(request.path("app"));
        app.deploy();
        return Response.seeOther("/apps/" + app.name() + "/deploy");
    }

    private Response showDeploy(Request request) throws IOException, NotFoundException {
        return view("deploy").put("app", App.get(request.path("app")));
    }

    private Response sendLog(Request request, String log) throws NotFoundException, IOException {
        App app = App.get(request.path("app"));
        int windowSize = 128 * 1024;
        try (FileChannel channel = FileChannel.open(app.logPath(log))) {
            long position = channel.size() - windowSize;
            if (position > 0) {
                channel.position(position);
            }
            ByteBuffer buffer = ByteBuffer.allocate(windowSize);
            channel.read(buffer);
            buffer.flip();
            return request1 -> {
                request1.exchange().getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                request1.exchange().getResponseSender().send(buffer);
            };
        }
    }

    private Response stop(Request request) throws Exception {
        App app = App.get(request.path("app"));
        request.flash(app.stop());
        return Response.seeOther("/apps/" + app.name() + "/logs");
    }

    private Response restart(Request request) throws Exception {
        App app = App.get(request.path("app"));
        request.flash(app.restart());
        return Response.seeOther("/apps/" + app.name() + "/logs");
    }

    private Config initAuthConfig() {
        OidcConfiguration oidcConfiguration = new OidcConfiguration();
        oidcConfiguration.setClientId(System.getenv("OIDC_CLIENT_ID"));
        oidcConfiguration.setSecret(System.getenv("OIDC_SECRET"));
        oidcConfiguration.setDiscoveryURI(System.getenv("OIDC_URL"));
        oidcConfiguration.setClientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        OidcClient<OidcProfile> oidcClient = new OidcClient<>(oidcConfiguration);
        oidcClient.setAuthorizationGenerator((context, profile) -> {
            profile.addRole("developer");
            return profile;
        });

        Clients clients = new Clients(baseUrl + "/callback", oidcClient);
        Config config = new Config(clients);
        config.addAuthorizer("developer", new RequireAnyRoleAuthorizer("developer"));
        return config;
    }

    private Response saveConfig(Request request) throws Exception {
        App app = App.get(request.path("app"));
        CommonProfile profile = request.account().getProfile();
        String config = request.formValue("config").replace("\r\n", "\n");
        if (app.config().equals(config)) {
            request.flash("Configuration unmodified.");
        } else {
            app.saveConfig(config, profile.getDisplayName(), profile.getEmail());
            request.flash("Configuration updated. Please restart or deploy.");
        }
        return Response.seeOther("/apps/" + app.name());
    }

    private View overview(Request request) throws Exception {
        return view("overview").put("app", App.get(request.path("app")));
    }

    private View logs(Request request) throws Exception {
        return view("logs").put("app", App.get(request.path("app")));
    }

    private View config(Request request) throws Exception {
        return view("config").put("app", App.get(request.path("app")));
    }

    private View index(Request request) throws Exception {
        return view("index");
    }

    public static void main(String args[]) {
        new AppDash().run();
    }

    private View view(String name) throws IOException {
        return new View("/appdash/" + name + ".ftlh")
                .put("apps", listApps());
    }

}
