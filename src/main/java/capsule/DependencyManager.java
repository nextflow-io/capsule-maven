/*
 * Capsule
 * Copyright (c) 2014-2016, Parallel Universe Software Co. All rights reserved.
 * 
 * This program and the accompanying materials are licensed under the terms 
 * of the Eclipse Public License v1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package capsule;

import static java.util.Collections.unmodifiableMap;

import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.AbstractForwardingRepositorySystemSession;
import org.eclipse.aether.ConfigurationProperties;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositoryException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.DependencyVisitor;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.AuthenticationSelector;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.Proxy;
import org.eclipse.aether.repository.ProxySelector;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.eclipse.aether.resolution.VersionRequest;
import org.eclipse.aether.resolution.VersionResult;
import org.eclipse.aether.util.artifact.ArtifactIdUtils;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;
import org.eclipse.aether.version.Version;

/**
 * Uses Aether as the Maven dependency manager.
 */
public class DependencyManager {
    /*
     * see http://git.eclipse.org/c/aether/aether-ant.git/tree/src/main/java/org/eclipse/aether/internal/ant/AntRepoSys.java
     */
    //<editor-fold desc="Constants">
    /////////// Constants ///////////////////////////////////
    private static final String PROP_OFFLINE = "capsule.offline";
    private static final String PROP_CONNECT_TIMEOUT = "capsule.connect.timeout";
    private static final String PROP_REQUEST_TIMEOUT = "capsule.request.timeout";
    private static final String PROP_USER_HOME = "user.home";

    private static final String ENV_CONNECT_TIMEOUT = "CAPSULE_CONNECT_TIMEOUT";
    private static final String ENV_REQUEST_TIMEOUT = "CAPSULE_REQUEST_TIMEOUT";

    static final Path DEFAULT_LOCAL_MAVEN = Paths.get(System.getProperty(PROP_USER_HOME), ".m2");

    private static final String LATEST_VERSION = "[0,)";
    public static final int LOG_NONE = 0;
    public static final int LOG_QUIET = 1;
    public static final int LOG_VERBOSE = 2;
    public static final int LOG_DEBUG = 3;
    private static final String LOG_PREFIX = "CAPSULE: ";

    private static final MavenUserSettings MVN_SETTINGS = MavenUserSettings.getInstance();

    static final Map<String, String> WELL_KNOWN_REPOS = unmodifiableMap(new HashMap<String, String>() {
        {
            put("central", "central(https://repo1.maven.org/maven2/)");
            put("central-http", "central(http://repo1.maven.org/maven2/)");
            put("jcenter", "jcenter(https://jcenter.bintray.com/)");
            put("jcenter-http", "jcenter(http://jcenter.bintray.com/)");
            put("local", "local(file:" + MVN_SETTINGS.getRepositoryHome() + ")");
        }
    });
    //</editor-fold>

    private final boolean forceRefresh;
    private final boolean offline;
    protected final RepositorySystem system;
    private final LocalRepository localRepo;
    private RepositorySystemSession session;
    private List<RemoteRepository> repos;
    private List<Dependency> managedDependencies;
    private final int logLevel;

    //<editor-fold desc="Construction and Setup">
    /////////// Construction and Setup ///////////////////////////////////
    public DependencyManager(Path localRepoPath, boolean forceRefresh, int logLevel) {
        this.logLevel = logLevel;
        this.forceRefresh = forceRefresh;
        this.offline = isPropertySet(PROP_OFFLINE, false);
        if (localRepoPath == null)
            localRepoPath = MVN_SETTINGS.getRepositoryHome();

        log(LOG_DEBUG, "DependencyManager - Offline: " + offline);
        log(LOG_DEBUG, "DependencyManager - Local repo: " + localRepoPath);

        this.localRepo = new LocalRepository(localRepoPath.toFile());
        this.system = newRepositorySystem();
    }

    public final DependencyManager setRepositories(List<String> repos, boolean allowSnapshots) {
        if (repos == null)
            //noinspection ArraysAsListWithZeroOrOneArgument
            repos = Arrays.asList("central");

        final List<RemoteRepository> rs = new ArrayList<>();
        for (String r : repos) {
            RemoteRepository repo = createRepo(r, allowSnapshots);
            if (!rs.contains(repo))
                rs.add(repo);
        }

        if (!Objects.equals(this.repos, rs)) {
            this.repos = rs;
            log(LOG_VERBOSE, "Dependency manager repositories: " + this.repos);
        }
        return this;
    }

    public final DependencyManager setManagedDependencies(List<String> managedDependencies) {
        this.managedDependencies = toManagedDependencies(managedDependencies);
        return this;
    }
    
    /** @noinspection UnusedParameters*/
    private RepositoryPolicy makeReleasePolicy(String repo) {
        return new RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_WARN);
    }

    private RepositoryPolicy makeSnapshotPolicy(String repo) {
        return makeReleasePolicy(repo);
    }

    private static RepositorySystem newRepositorySystem() {
        /*
         * We're using DefaultServiceLocator rather than Guice/Sisu because it's more lightweight.
         * This method pulls together the necessary Aether components and plugins.
         */
        final DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.setErrorHandler(new DefaultServiceLocator.ErrorHandler() {
            @Override
            public void serviceCreationFailed(Class<?> type, Class<?> impl, Throwable ex) {
                throw new RuntimeException("Service creation failed for type " + type.getName() + " with impl " + impl, ex);
            }
        });

        locator.addService(org.eclipse.aether.spi.connector.RepositoryConnectorFactory.class, org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory.class);
        locator.addService(org.eclipse.aether.spi.connector.transport.TransporterFactory.class, org.eclipse.aether.transport.http.HttpTransporterFactory.class);
        locator.addService(org.eclipse.aether.spi.connector.transport.TransporterFactory.class, org.eclipse.aether.transport.file.FileTransporterFactory.class);

        // Takari (support concurrent downloads)
        locator.setService(org.eclipse.aether.impl.SyncContextFactory.class, LockingSyncContextFactory.class);
        locator.setService(org.eclipse.aether.spi.io.FileProcessor.class, LockingFileProcessor.class);

        return locator.getService(RepositorySystem.class);
    }

    private RepositorySystemSession getSession() {
        if (session == null)
            session = newRepositorySession(system, localRepo);
        return session;
    }

    private RepositorySystemSession newRepositorySession(RepositorySystem system, LocalRepository localRepo) {
        final DefaultRepositorySystemSession s = MavenRepositorySystemUtils.newSession();

        s.setConfigProperty(ConfigurationProperties.CONNECT_TIMEOUT, propertyOrEnv(PROP_CONNECT_TIMEOUT, ENV_CONNECT_TIMEOUT));
        s.setConfigProperty(ConfigurationProperties.REQUEST_TIMEOUT, propertyOrEnv(PROP_REQUEST_TIMEOUT, ENV_REQUEST_TIMEOUT));
        // WARN: `ConflictResolver.CONFIG_PROP_VERBOSE` will retain (and mark) dependency graph duplicates such as conflict resolution losers since
        // http://git.eclipse.org/c/aether/aether-core.git/diff/aether-util/src/main/java/org/eclipse/aether/util/graph/transformer/ConflictResolver.java?id=141a3669d23ab67846b0c3ccef14eb0cdc70cee9
        s.setConfigProperty(ConflictResolver.CONFIG_PROP_VERBOSE, true);

        s.setOffline(offline);
        s.setUpdatePolicy(forceRefresh ? RepositoryPolicy.UPDATE_POLICY_ALWAYS : RepositoryPolicy.UPDATE_POLICY_NEVER);
        s.setLocalRepositoryManager(system.newLocalRepositoryManager(s, localRepo));        
        s.setMirrorSelector(MVN_SETTINGS.getMirrorSelector());
        s.setAuthenticationSelector(MVN_SETTINGS.getAuthSelector());
        final SystemProxySelector sysProxySelector; // proxy from environment variables
        s.setProxySelector((sysProxySelector = new SystemProxySelector(this)).isValid() ? sysProxySelector : MVN_SETTINGS.getProxySelector());
        
        // no need for these, as they're set by MavenRepositorySystemUtils.newSession()
//      s.setDependencyManager(newDependencyManager());
//      s.setDependencySelector(newDependencySelector());
//      s.setDependencyGraphTransformer(newConflictResolver());

        if (logLevel > LOG_NONE) {
            final PrintStream out = prefixStream(System.err, LOG_PREFIX);
            s.setTransferListener(new ConsoleTransferListener(isLogging(LOG_VERBOSE), out));
            s.setRepositoryListener(new ConsoleRepositoryListener(isLogging(LOG_VERBOSE), out));
        }

        return s;
    }

//    private static org.eclipse.aether.collection.DependencyManager newDependencyManager() {
//        return new org.eclipse.aether.util.graph.manager.ClassicDependencyManager();
//    }
    
//    private static DependencySelector newDependencySelector() {
//        return new org.eclipse.aether.util.graph.selector.AndDependencySelector(
//                new org.eclipse.aether.util.graph.selector.ScopeDependencySelector(null, Arrays.asList(JavaScopes.TEST, JavaScopes.PROVIDED)),
//                new org.eclipse.aether.util.graph.selector.OptionalDependencySelector(),
//                new org.eclipse.aether.util.graph.selector.ExclusionDependencySelector()
//        );
//    }
    
//    private static ConflictResolver newConflictResolver() {
//        return new ConflictResolver(
//                new org.eclipse.aether.util.graph.transformer.NearestVersionSelector(),
//                new org.eclipse.aether.util.graph.transformer.JavaScopeSelector(),
//                new org.eclipse.aether.util.graph.transformer.SimpleOptionalitySelector(),
//                new org.eclipse.aether.util.graph.transformer.JavaScopeDeriver()
//        );
//    }

    /** @noinspection unused*/
    public void setSystemProperties(Map<String, String> properties) {
        final Map<String, String> ps = Collections.unmodifiableMap(properties);
        if (getSession() instanceof DefaultRepositorySystemSession)
            ((DefaultRepositorySystemSession) getSession()).setSystemProperties(ps);
        else {
            final RepositorySystemSession s = getSession();
            this.session = new AbstractForwardingRepositorySystemSession() {
                @Override
                protected RepositorySystemSession getSession() {
                    return s;
                }

                @Override
                public Map<String, String> getSystemProperties() {
                    return ps;
                }
            };
        }
    }
    
    private RemoteRepository createRepo(String repo, boolean allowSnapshots) {
        final RemoteRepository.Builder builder = createRepoBuilder(repo);
        setPolicies(builder, allowSnapshots);
        setProxy(builder);
        setAuthentication(builder);
        return builder.build();
    }
    
    private static final Pattern PAT_REPO = Pattern.compile("(?<id>[^(]+)(\\((?<url>[^\\)]+)\\))?");
    
    // visible for testing
    static RemoteRepository.Builder createRepoBuilder(String repo) {
        final Matcher m = PAT_REPO.matcher(repo);
        if (!m.matches())
            throw new IllegalArgumentException("Could not parse repository: " + repo);

        final String id = m.group("id");
        String url = m.group("url");
        if (url == null && WELL_KNOWN_REPOS.containsKey(id))
            return createRepoBuilder(WELL_KNOWN_REPOS.get(id));
        if (url == null)
            url = id;
        
        return new RemoteRepository.Builder(id, "default", url);
    }
    
    private RemoteRepository.Builder setPolicies(RemoteRepository.Builder builder, boolean allowSnapshots) {
        final RemoteRepository tmp = builder.build(); // cheap operation
        final String id = tmp.getId();
        final String url = tmp.getUrl();
        
        RepositoryPolicy releasePolicy = makeReleasePolicy(id);
        RepositoryPolicy snapshotPolicy = allowSnapshots ? makeSnapshotPolicy(id) : new RepositoryPolicy(false, null, null);
        if (url.startsWith("file:")) {
            releasePolicy = new RepositoryPolicy(releasePolicy.isEnabled(), releasePolicy.getUpdatePolicy(), RepositoryPolicy.CHECKSUM_POLICY_IGNORE);
            snapshotPolicy = releasePolicy;
        }
        
        builder.setReleasePolicy(releasePolicy)
                .setSnapshotPolicy(snapshotPolicy);
        return builder;
    }
    
    private RemoteRepository.Builder setProxy(RemoteRepository.Builder builder) {
        final RemoteRepository tmp = builder.build(); // cheap operation
        
        final ProxySelector selector = getSession().getProxySelector();
        final Proxy proxy = selector.getProxy(tmp);
        if (proxy != null) {
            if (isLogging(LOG_DEBUG))
                log(LOG_DEBUG, String.format("Setting proxy: '%s' for dependency repo: %s", proxy, tmp.toString()));
            builder.setProxy(proxy);
        }
        
        return builder;
    }
    
    private RemoteRepository.Builder setAuthentication(RemoteRepository.Builder builder) {
        final RemoteRepository tmp = builder.build(); // cheap operation
        
        final AuthenticationSelector authenticationSelector = getSession().getAuthenticationSelector();
        final Authentication authentication = authenticationSelector.getAuthentication(tmp);
        if (authentication != null) {
            if (isLogging(LOG_DEBUG))
                log(LOG_DEBUG, String.format("Setting authentication for dependency repo: %s", tmp.toString()));
            builder.setAuthentication(authentication);
        }
        
        return builder;
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Operations">
    /////////// Operations ///////////////////////////////////
    private CollectRequest collect() {
        return new CollectRequest().setRepositories(repos).setManagedDependencies(managedDependencies);
    }

    public final void printDependencyTree(List<String> coords, String type, PrintStream out) {
        printDependencyTree(toDependencies(coords, type), out);
    }

    public final void printDependencyTree(List<Dependency> deps, PrintStream out) {
        printDependencyTree(collect().setDependencies(deps), out);
    }

    public final void printDependencyTree(String coords, String type, PrintStream out) {
        printDependencyTree(collect().setRoot(toDependency(coords, type)), out);
    }

    private void printDependencyTree(CollectRequest collectRequest, PrintStream out) {
        try {
            CollectResult collectResult = system.collectDependencies(getSession(), collectRequest);
            collectResult.getRoot().accept(new ConsoleDependencyGraphDumper(out));
        } catch (DependencyCollectionException e) {
            throw new RuntimeException(e);
        }
    }

    public final List<Path> resolveDependencies(List<String> coords, String type) {
        return resolve(collect().setDependencies(toDependencies(coords, type)));
    }

    public final List<Path> resolveDependency(String coords, String type) {
        return resolve(collect().setRoot(toDependency(coords, type))); // resolveDependencies(Collections.singletonList(coords), type);
    }

    public final Map<Dependency, List<Path>> resolveDependencies(List<Dependency> deps) {
        final List<DependencyNode> children = resolve0(collect().setDependencies(deps)).getRoot().getChildren();
        
        final Map<Dependency, List<Path>> resolved = new HashMap<>();
        for (DependencyNode dn : children) {
            final List<Path> jars = new ArrayList<>();
            resolved.put(clean(dn.getDependency()), jars);
            dn.accept(new DependencyVisitor() {
                @Override
                public final boolean visitEnter(DependencyNode node) {
                    if (!isLoser(node))
                        jars.add(path(node.getArtifact()));
                    return true;
                }

                @Override
                public final boolean visitLeave(DependencyNode node) {
                    return true;
                }
            });
        }
        return resolved;
    }

    private List<Path> resolve(CollectRequest collectRequest) {
        final List<Path> jars = new ArrayList<>();
        for (ArtifactResult artifactResult : resolve0(collectRequest).getArtifactResults())
            jars.add(path(artifactResult.getArtifact()));
        return jars;
    }

    private DependencyResult resolve0(CollectRequest collectRequest) {
        if (isLogging(LOG_DEBUG))
            log(LOG_DEBUG, "DependencyManager.resolve " + collectRequest);
        try {
            final DependencyRequest dependencyRequest = new DependencyRequest(collectRequest,
                    new DependencyFilter() {
                @Override
                public boolean accept(DependencyNode n, List<DependencyNode> parents) {
                    // Exclude conflict losers (which are marked but left in if verbose mode is enabled, see
                    // http://git.eclipse.org/c/aether/aether-core.git/diff/aether-util/src/main/java/org/eclipse/aether/util/graph/transformer/ConflictResolver.java?id=141a3669d23ab67846b0c3ccef14eb0cdc70cee9t
                    return !isLoser(n);
                }
            });
            final DependencyResult result = system.resolveDependencies(getSession(), dependencyRequest);
            if (isLogging(LOG_DEBUG))
                log(LOG_DEBUG, "DependencyManager.resolve: " + result);
            return result;
        } catch (DependencyResolutionException e) {
            throw new RuntimeException("Error resolving dependencies.", e);
        }
    }

    public final String getLatestVersion(String coords, String type) {
        return artifactToCoords(getLatestVersion0(coords, type));
    }

    protected Artifact getLatestVersion0(String coords, String type) {
        try {
            final Artifact artifact = coordsToArtifact(coords, type);
            final String version;
            if (isVersionRange(artifact.getVersion())) {
                final VersionRangeRequest request = new VersionRangeRequest().setRepositories(repos).setArtifact(artifact);
                final VersionRangeResult result = system.resolveVersionRange(getSession(), request);
                final Version highestVersion = result.getHighestVersion();
                version = highestVersion != null ? highestVersion.toString() : null;
            } else {
                final VersionRequest request = new VersionRequest().setRepositories(repos).setArtifact(artifact);
                final VersionResult result = system.resolveVersion(getSession(), request);
                version = result.getVersion();
            }
            if (version == null)
                throw new RuntimeException("Could not find any version of artifact " + coords + " (looking for: " + artifact + ")");
            return artifact.setVersion(version);
        } catch (RepositoryException e) {
            throw new RuntimeException(e);
        }
    }
    
    private static Path path(Artifact artifact) {
        return artifact.getFile().toPath().toAbsolutePath();
    }

    private boolean isLoser(DependencyNode dn) {
        final DependencyNode winner = (DependencyNode) dn.getData().get(ConflictResolver.NODE_DATA_WINNER);
        return winner != null && !ArtifactIdUtils.equalsId(dn.getArtifact(), winner.getArtifact());
    }
    
    private Dependency clean(Dependency d) {
        // necessary for dependency equality
        // SNAPSHOT dependencies get resolved to specific artifacts, so returned dependency is different from original
        final Artifact a = d.getArtifact();
        return new Dependency(new DefaultArtifact(a.getGroupId(), a.getArtifactId(), a.getClassifier(), a.getExtension(), a.getBaseVersion()),
                d.getScope(), d.getOptional(), d.getExclusions());
    }

    private boolean equalExceptVersion(Artifact a, Artifact b) {
        return Objects.equals(a.getGroupId(), b.getGroupId())
               && Objects.equals(a.getArtifactId(), b.getArtifactId())
               && Objects.equals(a.getExtension(), b.getExtension())
               && Objects.equals(a.getClassifier(), b.getClassifier())
               && Objects.equals(a.getBaseVersion(), b.getBaseVersion());
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Parsing">
    /////////// Parsing ///////////////////////////////////
    public static Dependency toDependency(String coords, String type) {
        return new Dependency(coordsToArtifact(coords, type), JavaScopes.RUNTIME, false, getExclusions(coords));
    }

    private static List<Dependency> toDependencies(List<String> coords, String type) {
        final List<Dependency> deps = new ArrayList<>(coords.size());
        for (String c : coords)
            deps.add(toDependency(c, type));
        return deps;
    }

    static String artifactToCoords(Artifact artifact) {
        if (artifact == null)
            return null;
        return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion()
                + ((artifact.getClassifier() != null && !artifact.getClassifier().isEmpty()) ? (":" + artifact.getClassifier()) : "");
    }

    private static boolean isVersionRange(String version) {
        return version.startsWith("(") || version.startsWith("[");
    }

    private static final Pattern PAT_DEPENDENCY = Pattern.compile("(?<groupId>[^:\\(]+):(?<artifactId>[^:\\(]+)(:(?<version>\\(?[^:\\(]*))?(:(?<classifier>[^:\\(]+))?(\\((?<exclusions>[^\\(\\)]*)\\))?");

    private static Artifact coordsToArtifact(String depString, String type) {
        final Matcher m = PAT_DEPENDENCY.matcher(depString);
        if (!m.matches())
            throw new IllegalArgumentException("Could not parse dependency: " + depString);

        final String groupId = m.group("groupId");
        final String artifactId = m.group("artifactId");
        final String version = emptyToNull(m.group("version"));
        final String classifier = m.group("classifier");
        return new DefaultArtifact(groupId, artifactId, classifier, type, version);
    }

    private static Collection<Exclusion> getExclusions(String depString) {
        final Matcher m = PAT_DEPENDENCY.matcher(depString);
        if (!m.matches())
            throw new IllegalArgumentException("Could not parse dependency: " + depString);

        if (m.group("exclusions") == null || m.group("exclusions").isEmpty())
            return null;

        final List<String> exclusionPatterns = Arrays.asList(m.group("exclusions").split(","));
        final List<Exclusion> exclusions = new ArrayList<>();
        for (String ex : exclusionPatterns) {
            String[] coords = ex.trim().split(":");
            if (coords.length != 2)
                throw new IllegalArgumentException("Illegal exclusion dependency coordinates: " + depString + " (in exclusion " + ex + ")");
            exclusions.add(new Exclusion(coords[0], coords[1], "*", "*"));
        }
        return exclusions;
    }

    protected static List<Dependency> toManagedDependencies(List<String> coords) {
        final List<Dependency> deps = new ArrayList<>(coords.size());
        for (String c : coords)
            deps.add(toManagedDependency(c));
        return deps;
    }

    private static final Pattern PAT_DEPENDENCY_MANAGEMENT = Pattern.compile("(?<groupId>[^:\\(]+):(?<artifactId>[^:\\(]+):(?<type>[^:\\(]*):(?<classifier>[^:\\(]*):(?<version>\\(?[^:\\(]+)");

    static Dependency toManagedDependency(String depString) {
        final Matcher m = PAT_DEPENDENCY_MANAGEMENT.matcher(depString);
        if (!m.matches())
            throw new IllegalArgumentException("Could not parse dependency management: " + depString);

        final String groupId = m.group("groupId");
        final String artifactId = m.group("artifactId");
        final String type = emptyToNull(m.group("type"));
        final String classifier = emptyToNull(m.group("classifier"));
        String version = emptyToNull(m.group("version"));
        if (version == null)
            throw new IllegalArgumentException("No version information is provided for managed dependency " + depString);
        
        boolean optional = false;
        if ("-".equals(version)) {
            version = null;
            optional = true;
        }
        final Artifact artifact =  new DefaultArtifact(groupId, artifactId, classifier, type, version);
        return new Dependency(artifact, JavaScopes.RUNTIME, optional);
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="General Utils">
    /////////// General Utils ///////////////////////////////////
    private static PrintStream prefixStream(PrintStream out, final String prefix) {
        return new PrintStream(out) {
            @Override
            public void println(String x) {
                super.println(prefix + x);
            }
        };
    }

    private static boolean hasArtifactPath(DependencyNode node) {
        Artifact a;
        return node != null
               && (a = node.getArtifact()) != null
               && a.getFile() != null;
    }

    private static String propertyOrEnv(String propName, String envVar) {
        String val = System.getProperty(propName);
        if (val == null)
            val = emptyToNull(System.getenv(envVar));
        return val;
    }

    private static Boolean isPropertySet(String property, boolean defaultValue) {
        final String val = System.getProperty(property);
        if (val == null)
            return defaultValue;
        return "".equals(val) | Boolean.parseBoolean(val);
    }

    static String emptyToNull(String s) {
        if (s == null)
            return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Logging">
    /////////// Logging ///////////////////////////////////
    final boolean isLogging(int level) {
        return level <= logLevel;
    }

    final void log(int level, String str) {
        if (isLogging(level))
            System.err.println(LOG_PREFIX + str);
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="DI Workarounds">
    /////////// DI Workarounds ///////////////////////////////////
    // necessary if we want to forgo Guice/Sisu injection and use DefaultServiceLocator instead
    private static final io.takari.filemanager.FileManager takariFileManager = new io.takari.filemanager.internal.DefaultFileManager();

    public static class LockingFileProcessor extends io.takari.aether.concurrency.LockingFileProcessor {
        public LockingFileProcessor() {
            super(takariFileManager);
        }
    }

    public static class LockingSyncContextFactory extends io.takari.aether.concurrency.LockingSyncContextFactory {
        public LockingSyncContextFactory() {
            super(takariFileManager);
        }
    }
    //</editor-fold>
}
