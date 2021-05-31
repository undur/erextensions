/*
 * Copyright (C) NetStruxr, Inc. All rights reserved.
 *
 * This software is published under the terms of the NetStruxr
 * Public Software License version 0.5, a copy of which has been
 * included with this distribution in the LICENSE.NPL file.  */
package er.extensions.appserver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.BindException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.log4j.Appender;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.webobjects.appserver.WOAction;
import com.webobjects.appserver.WOAdaptor;
import com.webobjects.appserver.WOApplication;
import com.webobjects.appserver.WOComponent;
import com.webobjects.appserver.WOContext;
import com.webobjects.appserver.WOCookie;
import com.webobjects.appserver.WOMessage;
import com.webobjects.appserver.WORequest;
import com.webobjects.appserver.WORequestHandler;
import com.webobjects.appserver.WOResourceManager;
import com.webobjects.appserver.WOResponse;
import com.webobjects.appserver.WOSession;
import com.webobjects.appserver.WOTimer;
import com.webobjects.appserver._private.WOComponentDefinition;
import com.webobjects.appserver._private.WODeployedBundle;
import com.webobjects.appserver._private.WOProperties;
import com.webobjects.foundation.NSArray;
import com.webobjects.foundation.NSBundle;
import com.webobjects.foundation.NSData;
import com.webobjects.foundation.NSDictionary;
import com.webobjects.foundation.NSForwardException;
import com.webobjects.foundation.NSKeyValueCoding;
import com.webobjects.foundation.NSLog;
import com.webobjects.foundation.NSMutableArray;
import com.webobjects.foundation.NSMutableDictionary;
import com.webobjects.foundation.NSMutableSet;
import com.webobjects.foundation.NSNotification;
import com.webobjects.foundation.NSNotificationCenter;
import com.webobjects.foundation.NSProperties;
import com.webobjects.foundation.NSPropertyListSerialization;
import com.webobjects.foundation.NSSelector;
import com.webobjects.foundation.NSTimestamp;
import com.webobjects.foundation.development.NSBundleFactory;

import er.extensions.ERXExtensions;
import er.extensions.ERXFrameworkPrincipal;
import er.extensions.appserver.ajax.ERXAjaxApplication;
import er.extensions.components._private.ERXWOForm;
import er.extensions.components._private.ERXWORepetition;
import er.extensions.components._private.ERXWOString;
import er.extensions.components._private.ERXWOTextField;
import er.extensions.foundation.ERXConfigurationManager;
import er.extensions.foundation.ERXExceptionUtilities;
import er.extensions.foundation.ERXMutableURL;
import er.extensions.foundation.ERXPatcher;
import er.extensions.foundation.ERXProperties;
import er.extensions.foundation.ERXRuntimeUtilities;
import er.extensions.foundation.ERXThreadStorage;
import er.extensions.localization.ERXLocalizer;
import er.extensions.statistics.ERXStats;
import x.ERXDeprecatedConstant;

/**
 * Improvements and fixes for WOApplication
 */

public abstract class ERXApplication extends ERXAjaxApplication {

	/** logging support */
	private static final Logger log = Logger.getLogger(ERXApplication.class);

	/** request logging support */
	private static final Logger requestHandlingLog = Logger.getLogger("er.extensions.ERXApplication.RequestHandling");

	/** statistic logging support */
	private static final Logger statsLog = Logger.getLogger("er.extensions.ERXApplication.Statistics");

	/** startup logging support */
	private static final Logger startupLog = Logger.getLogger("er.extensions.ERXApplication.Startup");

	private static boolean wasERXApplicationMainInvoked = false;

	/** empty array for adaptorExtensions */
	private static String[] myAppExtensions = {};

	/**
	 * Notification to get posted when we get an OutOfMemoryError or when memory
	 * passes the low memory threshold set in
	 * er.extensions.ERXApplication.memoryLowThreshold. You should register your
	 * caching classes for this notification so you can release memory.
	 * Registration should happen at launch time.
	 */
	public static final String LowMemoryNotification = "LowMemoryNotification";

	/**
	 * Notification to get posted when we have recovered from a LowMemory
	 * condition.
	 */
	public static final String LowMemoryResolvedNotification = "LowMemoryResolvedNotification";

	/**
	 * Notification to get posted when we are on the brink of running out of
	 * memory. By default, sessions will begin to be refused when this happens
	 * as well.
	 */
	public static final String StarvedMemoryNotification = "StarvedMemoryNotification";

	/**
	 * Notification to get posted when we have recovered from a StarvedMemory
	 * condition.
	 */
	public static final String StarvedMemoryResolvedNotification = "StarvedMemoryResolvedNotification";

	/**
	 * Notification to get posted when terminate() is called.
	 */
	public static final String ApplicationWillTerminateNotification = "ApplicationWillTerminateNotification";

	/**
	 * Buffer we reserve lowMemBufSize KB to release when we get an
	 * OutOfMemoryError, so we can post our notification and do other stuff
	 */
	private static byte lowMemBuffer[];

	/**
	 * Size of the memory in KB to reserve for low-mem situations, pulled form
	 * the system property
	 * <code>er.extensions.ERXApplication.lowMemBufferSize</code>. Default is 0,
	 * indicating no reserve.
	 */
	private static int lowMemBufferSize = 0;

	/**
	 * Property to control whether to exit on an OutOfMemoryError.
	 */
	private static final String AppShouldExitOnOutOfMemoryError = "er.extensions.AppShouldExitOnOutOfMemoryError";

	/**
	 * Notification to post when all bundles were loaded but before their
	 * principal was called
	 */
	public static final String AllBundlesLoadedNotification = "NSBundleAllBundlesLoaded";

	/**
	 * Notification to post when all bundles were loaded but before their
	 * principal was called
	 */
	public static final String ApplicationDidCreateNotification = "NSApplicationDidCreateNotification";

	/**
	 * Notification to post when all application initialization processes are
	 * complete (including migrations)
	 */
	public static final String ApplicationDidFinishInitializationNotification = "NSApplicationDidFinishInitializationNotification";

	private static NSDictionary propertiesFromArgv;

	/**
	 * Time that garbage collection was last called when checking memory.
	 */
	private long _lastGC = 0;

	/**
	 * Holds the value of the property
	 * er.extensions.ERXApplication.memoryStarvedThreshold
	 */
	private BigDecimal _memoryStarvedThreshold;

	/**
	 * Holds the value of the property
	 * er.extensions.ERXApplication.memoryLowThreshold
	 */
	private BigDecimal _memoryLowThreshold;

	/**
	 * The path rewriting pattern to match (@see _rewriteURL)
	 */
	private String _replaceApplicationPathPattern;

	/**
	 * The path rewriting replacement to apply to the matched pattern (@see
	 * _rewriteURL)
	 */
	private String _replaceApplicationPathReplace;

	/**
	 * The SSL host used by this application.
	 */
	private String _sslHost;

	/**
	 * The SSL port used by this application.
	 */
	private Integer _sslPort;

	/**
	 * Tracks whether or not _addAdditionalAdaptors has been called yet.
	 */
	private boolean _initializedAdaptors = false;

	/**
	 * To support load balancing with mod_proxy
	 */
	private String _proxyBalancerRoute = null;

	/**
	 * To support load balancing with mod_proxy
	 */
	private String _proxyBalancerCookieName = null;

	/**
	 * To support load balancing with mod_proxy
	 */
	private String _proxyBalancerCookiePath = null;

	/**
	 * The public host to use for complete url without request from a server (in
	 * background tasks)
	 */
	private String _publicHost;

	/**
	 * The time taken from invoking main, until the end of the application constructor
	 */
	private static long _startupTimeInMilliseconds;

	/**
	 * Copies the props from the command line to the static dict
	 * propertiesFromArgv.
	 * 
	 */
	private static void insertCommandLineArguments() {
		NSArray keys = propertiesFromArgv.allKeys();
		int count = keys.count();
		for (int i = 0; i < count; i++) {
			Object key = keys.objectAtIndex(i);
			Object value = propertiesFromArgv.objectForKey(key);
			NSProperties._setProperty((String) key, (String) value);
		}
	}

	static class AppClassLoader extends URLClassLoader {

		public static ClassLoader getAppClassLoader() {
			String classPath = System.getProperty("java.class.path");
			if (System.getProperty("com.webobjects.classpath") != null) {
				classPath += File.pathSeparator + System.getProperty("com.webobjects.classpath");
			}
			String files[] = classPath.split(File.pathSeparator);
			URL urls[] = new URL[files.length];
			for (int i = 0; i < files.length; i++) {
				String string = files[i];
				try {
					urls[i] = new File(string).toURI().toURL();
				}
				catch (MalformedURLException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			return new AppClassLoader(urls, Thread.currentThread().getContextClassLoader());
		}

		@Override
		public synchronized Class<?> loadClass(String s, boolean flag) throws ClassNotFoundException {
			SecurityManager securitymanager = System.getSecurityManager();
			if (securitymanager != null) {
				String s1 = s.replace('/', '.');
				if (s1.startsWith("[")) {
					int i = s1.lastIndexOf('[') + 2;
					if (i > 1 && i < s1.length()) {
						s1 = s1.substring(i);
					}
				}
				int j = s1.lastIndexOf('.');
				if (j != -1) {
					securitymanager.checkPackageAccess(s1.substring(0, j));
				}
			}
			return super.loadClass(s, flag);
		}

		AppClassLoader(URL aurl[], ClassLoader classloader) {
			super(aurl, classloader);
		}
	}

	private static Loader _loader;

	/**
	 * Responsible for classpath munging and ensuring all bundles are loaded
	 * 
	 * @property er.extensions.appserver.projectBundleLoading - to see logging
	 *           this has to be set on the command line by using
	 *           -Der.extensions.appserver.projectBundleLoading=DEBUG
	 * 
	 * @author ak
	 */
	public static class Loader {

		private JarChecker _checker;

		/** Holds the framework names during startup */
		private Set<String> allFrameworks;

		private Properties allBundleProps;
		private Properties defaultProperties;

		private List<URL> allBundlePropURLs = new ArrayList<>();

		private Properties readProperties(File file) {
			if (!file.exists()) {
				return null;
			}

			try {
				URL url = file.toURI().toURL();
				return readProperties(url);
			}
			catch (MalformedURLException e) {
				e.printStackTrace();
				return null;
			}
		}

		private Properties readProperties(URL url) {
			if (url == null) {
				return null;
			}

			try {
				Properties result = new Properties();
				result.load(url.openStream());
				urls.add(url);
				return result;
			}
			catch (MalformedURLException exception) {
				exception.printStackTrace();
				return null;
			}
			catch (IOException exception) {
				return null;
			}
		}

		private Properties readProperties(NSBundle bundle, String name) {
			if (bundle == null) {
				return null;
			}
			if (name == null) {
				URL url = bundle.pathURLForResourcePath("Properties");
				if (url != null) {
					urls.add(url);
				}
				return bundle.properties();
			}

			try( InputStream inputStream = bundle.inputStreamForResourcePath(name)) {
				if (inputStream == null) {
					return null;
				}
				Properties result = new Properties();
				result.load(inputStream);
				urls.add(bundle.pathURLForResourcePath(name));
				return result;
			}
			catch (MalformedURLException exception) {
				exception.printStackTrace();
				return null;
			}
			catch (IOException exception) {
				return null;
			}
		}

		/**
		 * Called prior to actually initializing the app. Defines framework load
		 * order, class path order, checks patches etc.
		 */
		public Loader(String[] argv) {
			wasERXApplicationMainInvoked = true;
			String cps[] = new String[] { "java.class.path", "com.webobjects.classpath" };
			propertiesFromArgv = NSProperties.valuesFromArgv(argv);
			defaultProperties = (Properties) NSProperties._getProperties().clone();
			allFrameworks = new HashSet<>();
			_checker = new JarChecker();

			for (int var = 0; var < cps.length; var++) {
				String cpName = cps[var];
				String cp = System.getProperty(cpName);
				if (cp != null) {
					String parts[] = cp.split(File.pathSeparator);
					String normalLibs = "";
					String systemLibs = "";
					String jarLibs = "";
					String frameworkPattern = ".*?/(\\w+)\\.framework/Resources/Java/\\1.jar".toLowerCase();
					String appPattern = ".*?/(\\w+)\\.woa/Contents/Resources/Java/\\1.jar".toLowerCase();
					String folderPattern = ".*?/Resources/Java/?$".toLowerCase();
					String projectPattern = ".*?/(\\w+)/bin$".toLowerCase();
					for (int i = 0; i < parts.length; i++) {
						String jar = parts[i];
						// Windows has \, we need to normalize
						String fixedJar = jar.replace(File.separatorChar, '/').toLowerCase();
						debugMsg("Checking: " + jar);
						// all patched frameworks here
						if (isSystemJar(jar)) {
							systemLibs += jar + File.pathSeparator;
						}
						else if (fixedJar.matches(frameworkPattern) || fixedJar.matches(appPattern) || fixedJar.matches(folderPattern)) {
							normalLibs += jar + File.pathSeparator;
						}
						else if (fixedJar.matches(projectPattern) || fixedJar.matches(".*?/erfoundation.jar") || fixedJar.matches(".*?/erwebobjects.jar")) {
							normalLibs += jar + File.pathSeparator;
						}
						else {
							jarLibs += jar + File.pathSeparator;
						}
						String bundle = jar.replaceAll(".*?[/\\\\](\\w+)\\.framework.*", "$1");
						String excludes = "(JavaVM|JavaWebServicesSupport|JavaEODistribution|JavaWebServicesGeneration|JavaWebServicesClient)";
						if (bundle.matches("^\\w+$") && !bundle.matches(excludes)) {
							String info = jar.replaceAll("(.*?[/\\\\]\\w+\\.framework/Resources/).*", "$1Info.plist");
							if (new File(info).exists()) {
								allFrameworks.add(bundle);
								debugMsg("Added Real Bundle: " + bundle);
							}
							else {
								debugMsg("Omitted: " + info);
							}
						}
						else if (jar.endsWith(".jar")) {
							String info = stringFromJar(jar, "Resources/Info.plist");
							if (info != null) {
								NSDictionary dict = (NSDictionary) NSPropertyListSerialization.propertyListFromString(info);
								bundle = (String) dict.objectForKey("CFBundleExecutable");
								allFrameworks.add(bundle);
								debugMsg("Added Jar bundle: " + bundle);
							}
						}
						// MS: This is totally hacked in to make Wonder startup
						// properly with the new rapid turnaround. It's
						// duplicating (poorly)
						// code from NSProjectBundle. I'm not sure we actually
						// need this anymore, because NSBundle now fires an "all
						// bundles loaded" event.
						else if (jar.endsWith("/bin") && new File(new File(jar).getParentFile(), ".project").exists()) {
							// AK: I have no idea if this is checked anywhere
							// else, but this keeps is from having to set it in
							// the VM args.
							debugMsg("Plain bundle: " + jar);
							for (File classpathFolder = new File(bundle); classpathFolder != null && classpathFolder.exists(); classpathFolder = classpathFolder.getParentFile()) {
								File projectFile = new File(classpathFolder, ".project");
								if (projectFile.exists()) {
									try {
										boolean isBundle = false;
										Document projectDocument = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(projectFile);
										projectDocument.normalize();
										NodeList natureNodeList = projectDocument.getElementsByTagName("nature");
										for (int natureNodeNum = 0; !isBundle && natureNodeNum < natureNodeList.getLength(); natureNodeNum++) {
											Element natureContainerNode = (Element) natureNodeList.item(natureNodeNum);
											Node natureNode = natureContainerNode.getFirstChild();
											String nodeValue = natureNode.getNodeValue();
											// AK: we don't actually add apps to
											// the bundle process (Mike, why
											// not!?)
											if (nodeValue != null && nodeValue.startsWith("org.objectstyle.wolips.") && !nodeValue.contains("application")) {
												isBundle = true;
											}
										}
										if (isBundle) {
											System.setProperty("NSProjectBundleEnabled", "true");
											String bundleName = classpathFolder.getName();

											File buildPropertiesFile = new File(classpathFolder, "build.properties");
											if (buildPropertiesFile.exists()) {
												Properties buildProperties = new Properties();
												buildProperties.load(new FileReader(buildPropertiesFile));
												if (buildProperties.get("project.name") != null) {
													// the project folder might
													// be named differently than
													// the actual bundle name
													bundleName = (String) buildProperties.get("project.name");
												}
											}

											allFrameworks.add(bundleName);
											debugMsg("Added Binary Bundle (Project bundle): " + bundleName);
										}
										else {
											debugMsg("Skipping binary bundle: " + jar);
										}
									}
									catch (Throwable t) {
										System.err.println("Skipping '" + projectFile + "': " + t);
									}
									break;
								}
								debugMsg("Skipping, no project: " + projectFile);
							}
						}
					}
					String newCP = "";
					if (normalLibs.length() > 1) {
						normalLibs = normalLibs.substring(0, normalLibs.length() - 1);
						newCP += normalLibs;
					}
					if (systemLibs.length() > 1) {
						systemLibs = systemLibs.substring(0, systemLibs.length() - 1);
						newCP += (newCP.length() > 0 ? File.pathSeparator : "") + systemLibs;
					}
					if (jarLibs.length() > 1) {
						jarLibs = jarLibs.substring(0, jarLibs.length() - 1);
						newCP += (newCP.length() > 0 ? File.pathSeparator : "") + jarLibs;
					}
					String jars[] = newCP.split(File.pathSeparator);
					for (int i = 0; i < jars.length; i++) {
						String jar = jars[i];
						_checker.processJar(jar);
					}
					if (System.getProperty("_DisableClasspathReorder") == null) {
						System.setProperty(cpName, newCP);
					}
				}
			}
			NSNotificationCenter.defaultCenter().addObserver(this, new NSSelector("bundleDidLoad", ERXDeprecatedConstant.NotificationClassArray), "NSBundleDidLoadNotification", null);
		}

		// for logging before logging has been setup and configured by loading
		// the properties files
		private void debugMsg(String msg) {
			if ("DEBUG".equals(System.getProperty("er.extensions.appserver.projectBundleLoading"))) {
				System.out.println(msg);
			}
		}

		public boolean didLoad() {
			return (allFrameworks != null && allFrameworks.size() == 0);
		}

		private NSBundle mainBundle() {
			NSBundle mainBundle = null;
			String mainBundleName = NSProperties._mainBundleName();
			if (mainBundleName != null) {
				mainBundle = NSBundle.bundleForName(mainBundleName);
			}
			if (mainBundle == null) {
				mainBundle = NSBundle.mainBundle();
			}
			if (mainBundle == null) {
				// AK: when we get here, the main bundle wasn't inited yet
				// so we do it ourself...

				if (isDevelopmentModeSafe() &&
						ERXConfigurationManager.defaultManager().isDeployedAsServlet()) {
					// bundle-less builds do not appear to work when running in
					// servlet mode, so make it prefer the legacy bundle style
					NSBundleFactory.registerBundleFactory(new com.webobjects.foundation.development.NSLegacyBundle.Factory());
//					throw new RuntimeException( "This was using code from ERFoundation. Killed." );
				}

				try {
					Field ClassPath = NSBundle.class.getDeclaredField("ClassPath");
					ClassPath.setAccessible(true);
					if (ClassPath.get(NSBundle.class) != null) {
						Method init = NSBundle.class.getDeclaredMethod("InitMainBundle");
						init.setAccessible(true);
						init.invoke(NSBundle.class);
					}
				}
				catch (Exception e) {
					System.err.println(e);
					e.printStackTrace();
					System.exit(1);
				}
				mainBundle = NSBundle.mainBundle();
			}
			return mainBundle;
		}

		/**
		 * Will be called after each bundle load. We use it to know when the
		 * last bundle loaded so we can post a notification for it. Note that
		 * the bundles will get loaded in the order of the classpath but the
		 * main bundle will get loaded last. So in order to set the properties
		 * correctly, we first add all the props that are not already set, then
		 * we add the main bundle and the WebObjects.properties and finally the
		 * command line props.
		 * 
		 * @param n
		 */
		public void bundleDidLoad(NSNotification n) {
			NSBundle bundle = (NSBundle) n.object();
			if (allFrameworks.contains(bundle.name())) {
				allFrameworks.remove(bundle.name());
				debugMsg("Loaded " + bundle.name() + ". Remaining: " + allFrameworks);
			}
			else if (bundle.isFramework()) {
				debugMsg("Loaded unexpected framework bundle '" + bundle.name() + "'. Ensure your build.properties settings like project.name match the bundle name (including case).");
			}
			if (allBundleProps == null) {
				allBundleProps = new Properties();
			}

			String userName = propertyFromCommandLineFirst("user.name");

			applyIfUnset(readProperties(bundle, "Properties." + userName));
			applyIfUnset(readProperties(bundle, null));

			if (allFrameworks.size() == 0) {
				mainProps = null;
				mainUserProps = null;

				collectMainProps(userName);

				allBundleProps.putAll(mainProps);
				if (mainUserProps != null) {
					allBundleProps.putAll(mainUserProps);
				}

				String userHome = propertyFromCommandLineFirst("user.home");
				Properties userHomeProps = null;
				if (userHome != null && userHome.length() > 0) {
					userHomeProps = readProperties(new File(userHome, "WebObjects.properties"));
				}

				if (userHomeProps != null) {
					allBundleProps.putAll(userHomeProps);
				}

				Properties props = NSProperties._getProperties();
				props.putAll(allBundleProps);

				NSProperties._setProperties(props);

				insertCommandLineArguments();
				if (userHomeProps != null) {
					urls.add(0, urls.remove(urls.size() - 1));
				}
				if (mainUserProps != null) {
					urls.add(0, urls.remove(urls.size() - 1));
				}
				urls.add(0, urls.remove(urls.size() - 1));
				// System.out.print(urls);
				NSNotificationCenter.defaultCenter().postNotification(new NSNotification(AllBundlesLoadedNotification, NSKeyValueCoding.NullValue));
			}
		}

		private List<URL> urls = new ArrayList<>();
		private Properties mainProps;
		private Properties mainUserProps;

		private String propertyFromCommandLineFirst(String key) {
			String result = (String) propertiesFromArgv.valueForKey(key);
			if (result == null) {
				result = NSProperties.getProperty(key);
			}
			return result;
		}

		private void collectMainProps(String userName) {
			NSBundle mainBundle = mainBundle();

			if (mainBundle != null) {
				mainUserProps = readProperties(mainBundle, "Properties." + userName);
				mainProps = readProperties(mainBundle, "Properties");
			}
			if (mainProps == null) {
				String woUserDir = NSProperties.getProperty("webobjects.user.dir");
				if (woUserDir == null) {
					woUserDir = System.getProperty("user.dir");
				}
				mainUserProps = readProperties(new File(woUserDir, "Contents" + File.separator + "Resources" + File.separator + "Properties." + userName));
				mainProps = readProperties(new File(woUserDir, "Contents" + File.separator + "Resources" + File.separator + "Properties"));
			}

			if (mainProps == null) {
				ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

				try {
					Enumeration<URL> jarBundles = classLoader.getResources("Resources/Properties");

					URL propertiesPath = null;
					URL userPropertiesPath = null;
					String mainBundleName = NSProperties._mainBundleName();

					// Look for a jar file name like: myapp[-1.0][-SNAPSHOT].jar
					Pattern mainBundleJarPattern = Pattern.compile("\\b" + mainBundleName.toLowerCase() + "[-\\.\\d]*(snapshot)?\\.jar");

					while (jarBundles.hasMoreElements()) {
						URL url = jarBundles.nextElement();

						String urlAsString = url.toString();

						if (mainBundleJarPattern.matcher(urlAsString.toLowerCase()).find()) {
							try {
								propertiesPath = new URL(URLDecoder.decode(urlAsString, StandardCharsets.UTF_8));
								userPropertiesPath = new URL(propertiesPath.toExternalForm() + userName);
							}
							catch (MalformedURLException exception) {
								exception.printStackTrace();
							}

							break;
						}
					}
					mainProps = readProperties(propertiesPath);
					mainUserProps = readProperties(userPropertiesPath);
				}
				catch (IOException exception) {
					exception.printStackTrace();
				}
			}

			if (mainProps == null) {
				throw new IllegalStateException("Main bundle 'Properties' file can't be read.  Did you run as a Java Application instead of a WOApplication in WOLips?\nPlease post your deployment configuration in the Wonder mailing list.");
			}
		}

		private void applyIfUnset(Properties bundleProps) {
			if (bundleProps == null)
				return;
			for (Iterator iter = bundleProps.entrySet().iterator(); iter.hasNext();) {
				Map.Entry entry = (Map.Entry) iter.next();
				if (!allBundleProps.containsKey(entry.getKey())) {
					allBundleProps.setProperty((String) entry.getKey(), (String) entry.getValue());
				}
			}
		}

		private boolean isSystemJar(String jar) {
			// check system path
			String systemRoot = System.getProperty("WORootDirectory");
			if (systemRoot != null) {
				if (jar.startsWith(systemRoot)) {
					return true;
				}
			}
			// check maven path
			if (jar.indexOf("webobjects" + File.separator + "apple") > 0) {
				return true;
			}
			// check mac path
			if (jar.indexOf("System" + File.separator + "Library") > 0) {
				return true;
			}
			// check win path
			if (jar.indexOf("Apple" + File.separator + "Library") > 0) {
				return true;
			}
			// if embedded, check explicit names
			if (jar.matches("Frameworks[/\\\\]Java(Foundation|EOControl|EOAccess|WebObjects).*")) {
				return true;
			}
			return false;
		}

		private String stringFromJar(String jar, String path) {
			JarFile f;
			InputStream is = null;
			try {
				if (!new File(jar).exists()) {
					ERXApplication.log.warn("Will not process jar '" + jar + "' because it cannot be found ...");
					return null;
				}
				f = new JarFile(jar);
				JarEntry e = (JarEntry) f.getEntry(path);
				if (e != null) {
					is = f.getInputStream(e);
					ByteArrayOutputStream bout = new ByteArrayOutputStream();
					int read = -1;
					byte[] buf = new byte[1024 * 50];
					while ((read = is.read(buf)) != -1) {
						bout.write(buf, 0, read);
					}

					String content = new String(bout.toByteArray(), StandardCharsets.UTF_8);
					return content;
				}
				return null;
			}
			catch (FileNotFoundException e1) {
				return null;
			}
			catch (IOException e1) {
				throw NSForwardException._runtimeExceptionForThrowable(e1);
			}
			finally {
				if (is != null) {
					try {
						is.close();
					}
					catch (IOException e) {
						// ignore
					}
				}
			}
		}
	}

	// You should not use ERXShutdownHook when deploying as servlet.
	protected static boolean enableERXShutdownHook() {
		return ERXProperties.booleanForKeyWithDefault("er.extensions.ERXApplication.enableERXShutdownHook", true);
	}

	/**
	 * Called when the application starts up and saves the command line
	 * arguments for {@link ERXConfigurationManager}.
	 * 
	 * @see WOApplication#main(String[], Class)
	 */
	public static void main(String argv[], Class applicationClass) {
		_startupTimeInMilliseconds = System.currentTimeMillis();

		setup(argv);

		if (enableERXShutdownHook()) {
			ERXShutdownHook.initERXShutdownHook();
		}

		WOApplication.main(argv, applicationClass);
	}

	/**
	 * <p>
	 * Terminates a different instance of the same application that may already
	 * be running.<br>
	 * Only in dev mode.
	 * </p>
	 * <p>
	 * Set the property "er.extensions.ERXApplication.allowMultipleDevInstances"
	 * to "true" if you need to run multiple instances in dev mode.
	 * </p>
	 * 
	 * @return true if a previously running instance was stopped.
	 */
	private static boolean stopPreviousDevInstance() {
		if (!isDevelopmentModeSafe() ||
				ERXProperties.booleanForKeyWithDefault("er.extensions.ERXApplication.allowMultipleDevInstances", false)) {
			return false;
		}

		if (!(application().wasMainInvoked())) {
			return false;
		}

		try {
			ERXMutableURL adapterUrl = new ERXMutableURL(application().cgiAdaptorURL());
			if (application().host() == null) {
				adapterUrl.setHost("localhost");
			}
			adapterUrl.appendPath(application().name() + application().applicationExtension());

			if (application().isDirectConnectEnabled()) {
				adapterUrl.setPort((Integer) application().port());
			}
			else {
				adapterUrl.appendPath("-" + application().port());
			}

			adapterUrl.appendPath(application().directActionRequestHandlerKey() + "/stop");
			URL url = adapterUrl.toURL();

			log.debug("Stopping previously running instance of " + application().name());

			URLConnection connection = url.openConnection();
			connection.getContent();

			Thread.sleep(2000);

			return true;
		}
		catch (Throwable e) {
			e.printStackTrace();
		}
		return false;
	}

	/**
	 * Utility class to track down duplicate items in the class path. Reports
	 * duplicate packages and packages that are present in different versions.
	 * 
	 * @author ak
	 */
	public static class JarChecker {
		private static class Entry {
			long _size;
			String _jar;

			public Entry(long aL, String jar) {
				_size = aL;
				_jar = jar;
			}

			public long size() {
				return _size;
			}

			public String jar() {
				return _jar;
			}

			@Override
			public boolean equals(Object other) {
				if (other != null && other instanceof Entry) {
					return ((Entry) other).size() == size();
				}
				return false;
			}

			@Override
			public int hashCode() {
				return (int) _size;
			}

			@Override
			public String toString() {
				return size() + "->" + jar();
			}
		}

		private NSMutableDictionary<String, NSMutableArray<String>> packages = new NSMutableDictionary<>();

		private NSMutableDictionary<String, NSMutableSet<Entry>> classes = new NSMutableDictionary<>();

		private void processJar(String jar) {
			File jarFile = new File(jar);
			if (!jarFile.exists() || jarFile.isDirectory()) {
				return;
			}
			try( JarFile f = new JarFile(jar)) {
				for (Enumeration<JarEntry> enumerator = f.entries(); enumerator.hasMoreElements();) {
					JarEntry entry = enumerator.nextElement();
					String name = entry.getName();
					if (entry.getName().endsWith("/") && !(name.matches("^\\w+/$") || name.startsWith("META-INF"))) {
						NSMutableArray<String> bundles = packages.objectForKey(name);
						if (bundles == null) {
							bundles = new NSMutableArray<>();
							packages.setObjectForKey(bundles, name);
						}
						bundles.addObject(jar);
					}
					else if (!(name.startsWith("src") || name.startsWith("META-INF"))) {
						Entry e = new Entry(entry.getSize(), jar);
						NSMutableSet<Entry> set = classes.objectForKey(name);
						if (set == null) {
							set = new NSMutableSet<>();
							classes.setObjectForKey(set, name);
						}
						set.addObject(e);
					}
				}
			}
			catch (IOException e) {
				startupLog.error("Error in processing jar: " + jar, e);
			}
		}

		private void reportErrors() {
			StringBuilder sb = new StringBuilder();
			String message = null;

			NSMutableArray<String> keys = new NSMutableArray<>( packages.allKeys() );
			Collections.sort(keys);

			for (Enumeration<String> enumerator = keys.objectEnumerator(); enumerator.hasMoreElements();) {
				String packageName = enumerator.nextElement();
				NSMutableArray<String> bundles = packages.objectForKey(packageName);
				if (bundles.count() > 1) {
					sb.append('\t').append(packageName).append("->").append(bundles).append('\n');
				}
			}
			message = sb.toString();
			if (message.length() > 0) {
				startupLog.debug("The following packages appear multiple times:\n" + message);
			}
			sb = new StringBuilder();
			NSMutableSet<String> classPackages = new NSMutableSet<>();
			keys = new NSMutableArray<>( classes.allKeys());
			Collections.sort(keys);
			
			for (Enumeration<String> enumerator = keys.objectEnumerator(); enumerator.hasMoreElements();) {
				String className = enumerator.nextElement();
				String packageName = className.replaceAll("/[^/]+?$", "");
				NSMutableSet<Entry> bundles = classes.objectForKey(className);
				if (bundles.count() > 1 && !classPackages.containsObject(packageName)) {
					sb.append('\t').append(packageName).append("->").append(bundles).append('\n');
					classPackages.addObject(packageName);
				}
			}
			message = sb.toString();
			if (message.length() > 0) {
				startupLog.debug("The following packages have different versions, you should remove the version you don't want:\n" + message);
			}
		}
	}

	/**
	 * This heuristic to determine if an application is deployed as servlet
	 * relays on the fact, that contextClassName() is set WOServletContext or
	 * ERXWOServletContext
	 * 
	 * @return true if the application is deployed as servlet.
	 */
	public boolean isDeployedAsServlet() {
		return contextClassName().contains("Servlet"); // i.e one of
														// WOServletContext or
														// ERXWOServletContext
	}

	/**
	 * Called prior to actually initializing the app. Defines framework load
	 * order, class path order, checks patches etc.
	 */
	public static void setup(String[] argv) {
		_loader = new Loader(argv);
		if (System.getProperty("_DisableClasspathReorder") == null) {
			ClassLoader loader = AppClassLoader.getAppClassLoader();
			Thread.currentThread().setContextClassLoader(loader);
		}
		ERXConfigurationManager.defaultManager().setCommandLineArguments(argv);
		ERXFrameworkPrincipal.setUpFrameworkPrincipalClass(ERXExtensions.class);
		// NSPropertiesCoordinator.loadProperties();

		if (enableERXShutdownHook()) {
			ERXShutdownHook.useMe();
		}
	}

	/**
	 * Installs several bugfixes and enhancements to WODynamicElements. Sets the
	 * Context class name to "er.extensions.ERXWOContext" if it is "WOContext".
	 * Patches ERXWOForm, ERXWOFileUpload, ERXWOText to be used instead of
	 * WOForm, WOFileUpload, WOText.
	 */
	protected void installPatches() {
		ERXPatcher.installPatches();
		if (contextClassName().equals("WOContext")) {
			setContextClassName(ERXWOContext.class.getName());
		}

		ERXPatcher.setClassForName(ERXWOForm.class, "WOForm");
		ERXPatcher.setClassForName(ERXWORepetition.class, "WORepetition");

		// use our localizing string class
		// works around #3574558
		if (ERXLocalizer.isLocalizationEnabled()) {
			ERXPatcher.setClassForName(ERXWOString.class, "WOString");
			ERXPatcher.setClassForName(ERXWOTextField.class, "WOTextField");
		}
	}

	@Override
	public WOResourceManager createResourceManager() {
		return new ERXResourceManager();
	}

	/**
	 * The ERXApplication constructor.
	 */
	public ERXApplication() {
		super();

		/*
		 * ERXComponentRequestHandler is a patched version of the original
		 * WOComponentRequestHandler This method will tell Application to used
		 * the patched, the patched version will disallow direct component
		 * access by name If you want to use the unpatched version set the
		 * property ERXDirectComponentAccessAllowed to true
		 */
		if (!ERXProperties.booleanForKeyWithDefault("ERXDirectComponentAccessAllowed", false)) {
			ERXComponentRequestHandler erxComponentRequestHandler = new ERXComponentRequestHandler();
			registerRequestHandler(erxComponentRequestHandler, componentRequestHandlerKey());
		}

		ERXStats.initStatisticsIfNecessary();

		// WOFrameworksBaseURL and WOApplicationBaseURL properties are broken in 5.4.
		// This is the workaround.
		frameworksBaseURL();
		applicationBaseURL();
		if (System.getProperty("WOFrameworksBaseURL") != null) {
			setFrameworksBaseURL(System.getProperty("WOFrameworksBaseURL"));
		}
		if (System.getProperty("WOApplicationBaseURL") != null) {
			setApplicationBaseURL(System.getProperty("WOApplicationBaseURL"));
		}

		if (!ERXConfigurationManager.defaultManager().isDeployedAsServlet() && (!wasERXApplicationMainInvoked || _loader == null)) {
			_displayMainMethodWarning();
		}
		// try {
		// NSBundle.mainBundle().versionString();
		// } catch (NoSuchMethodError e) {
		// throw new RuntimeException("No versionString() method in NSBundle
		// found. \nThis means your class path is incorrect. Adjust it so that
		// ERJars comes before JavaFoundation.");
		// }
		if (_loader == null) {
			System.out.println("No loader: " + System.getProperty("java.class.path"));
		}
		else if (!_loader.didLoad()) {
			throw new RuntimeException("ERXExtensions have not been initialized. Debugging information can be enabled by adding the JVM argument: '-Der.extensions.appserver.projectBundleLoading=DEBUG'. Please report the classpath and the rest of the bundles to the Wonder mailing list: " + "\nRemaining frameworks: " + (_loader == null ? "none" : _loader.allFrameworks) + "\nClasspath: " + System.getProperty("java.class.path"));
		}
		if ("JavaFoundation".equals(NSBundle.mainBundle().name())) {
			throw new RuntimeException("Your main bundle is \"JavaFoundation\".  You are not launching this WO application properly.  If you are using Eclipse, most likely you launched your WOA as a \"Java Application\" instead of a \"WO Application\".");
		}

		// ak: telling Log4J to re-init the Console appenders so we get logging into WOOutputPath again
		for (Enumeration e = Logger.getRootLogger().getAllAppenders(); e.hasMoreElements();) {
			Appender appender = (Appender) e.nextElement();
			if (appender instanceof ConsoleAppender) {
				ConsoleAppender app = (ConsoleAppender) appender;
				app.activateOptions();
			}
		}

		if (_loader != null) {
			_loader._checker.reportErrors();
			_loader._checker = null;
		}

		didCreateApplication();
		NSNotificationCenter.defaultCenter().postNotification(new NSNotification(ApplicationDidCreateNotification, this));
		installPatches();
		lowMemBufferSize = ERXProperties.intForKeyWithDefault("er.extensions.ERXApplication.lowMemBufferSize", 0);
		if (lowMemBufferSize > 0) {
			lowMemBuffer = new byte[lowMemBufferSize];
		}
		registerRequestHandler(new ERXDirectActionRequestHandler(), directActionRequestHandlerKey());
		if (_rapidTurnaroundActiveForAnyProject() && isDirectConnectEnabled()) {
			registerRequestHandler(new ERXStaticResourceRequestHandler(), "_wr_");
		}
		registerRequestHandler(new ERXDirectActionRequestHandler(ERXDirectAction.class.getName(), "stats", false), "erxadm");

		String defaultEncoding = System.getProperty("er.extensions.ERXApplication.DefaultEncoding");
		if (defaultEncoding != null) {
			log.debug("Setting default encoding to \"" + defaultEncoding + "\"");
			setDefaultEncoding(defaultEncoding);
		}

		String defaultMessageEncoding = System.getProperty("er.extensions.ERXApplication.DefaultMessageEncoding");
		if (defaultMessageEncoding != null) {
			log.debug("Setting WOMessage default encoding to \"" + defaultMessageEncoding + "\"");
			WOMessage.setDefaultEncoding(defaultMessageEncoding);
		}

		log.info("Wonder version: " + ERXProperties.wonderVersion());

		// Configure the WOStatistics CLFF logging since it can't be controlled by a property, grrr.
		configureStatisticsLogging();

		NSNotificationCenter.defaultCenter().addObserver(this, new NSSelector("finishInitialization", ERXDeprecatedConstant.NotificationClassArray), WOApplication.ApplicationWillFinishLaunchingNotification, null);

		NSNotificationCenter.defaultCenter().addObserver(this, new NSSelector("didFinishLaunching", ERXDeprecatedConstant.NotificationClassArray), WOApplication.ApplicationDidFinishLaunchingNotification, null);

		NSNotificationCenter.defaultCenter().addObserver(this, new NSSelector("addBalancerRouteCookieByNotification", new Class[] { NSNotification.class }), WORequestHandler.DidHandleRequestNotification, null);

		_memoryStarvedThreshold = ERXProperties.bigDecimalForKey("er.extensions.ERXApplication.memoryThreshold"); // MS: Kept around for backwards compat, replaced by memoryStarvedThreshold now
		_memoryStarvedThreshold = ERXProperties.bigDecimalForKeyWithDefault("er.extensions.ERXApplication.memoryStarvedThreshold", _memoryStarvedThreshold);
		_memoryLowThreshold = ERXProperties.bigDecimalForKeyWithDefault("er.extensions.ERXApplication.memoryLowThreshold", _memoryLowThreshold);

		_replaceApplicationPathPattern = ERXProperties.stringForKey("er.extensions.ERXApplication.replaceApplicationPath.pattern");
		if (_replaceApplicationPathPattern != null && _replaceApplicationPathPattern.length() == 0) {
			_replaceApplicationPathPattern = null;
		}
		_replaceApplicationPathReplace = ERXProperties.stringForKey("er.extensions.ERXApplication.replaceApplicationPath.replace");

		if (_replaceApplicationPathPattern == null && rewriteDirectConnectURL()) {
			_replaceApplicationPathPattern = "/cgi-bin/WebObjects/" + name() + applicationExtension();
			if (_replaceApplicationPathReplace == null) {
				_replaceApplicationPathReplace = "";
			}
		}

		_publicHost = ERXProperties.stringForKeyWithDefault("er.extensions.ERXApplication.publicHost", host());
	}

	/**
	 * Called, for example, when refuse new sessions is enabled and the request
	 * contains an expired session. If mod_rewrite is being used we don't want
	 * the adaptor prefix being part of the redirect.
	 * 
	 * @see com.webobjects.appserver.WOApplication#_newLocationForRequest(com.webobjects.appserver.WORequest)
	 */
	@Override
	public String _newLocationForRequest(WORequest aRequest) {
		return _rewriteURL(super._newLocationForRequest(aRequest));
	}

	/**
	 * Configures the statistics logging for a given application. By default
	 * will log to a file &lt;base log directory&gt;/&lt;WOApp
	 * Name&gt;-&lt;host&gt;-&lt;port&gt;.log if the base log path is defined.
	 * The base log path is defined by the property
	 * <code>er.extensions.ERXApplication.StatisticsBaseLogPath</code> The
	 * default log rotation frequency is 24 hours, but can be changed by setting
	 * in milliseconds the property
	 * <code>er.extensions.ERXApplication.StatisticsLogRotationFrequency</code>
	 */
	public void configureStatisticsLogging() {
		String statisticsBasePath = System.getProperty("er.extensions.ERXApplication.StatisticsBaseLogPath");
		if (statisticsBasePath != null) {
			// Defaults to a single day
			int rotationFrequency = ERXProperties.intForKeyWithDefault("er.extensions.ERXApplication.StatisticsLogRotationFrequency", 24 * 60 * 60 * 1000);
			String logPath = statisticsBasePath + File.separator + name() + "-" + ERXConfigurationManager.defaultManager().hostName() + "-" + port() + ".log";
			if (log.isDebugEnabled()) {
				log.debug("Configured statistics logging to file path \"" + logPath + "\" with rotation frequency: " + rotationFrequency);
			}
			statisticsStore().setLogFile(logPath, rotationFrequency);
		}
	}

	/**
	 * Notification method called when the application posts the notification
	 * {@link WOApplication#ApplicationWillFinishLaunchingNotification}. This
	 * method calls subclasses' {@link #finishInitialization} method.
	 * 
	 * @param n
	 *            notification that is posted after the WOApplication has been
	 *            constructed, but before the application is ready for accepting
	 *            requests.
	 */
	public final void finishInitialization(NSNotification n) {
		finishInitialization();
		NSNotificationCenter.defaultCenter().postNotification(new NSNotification(ERXApplication.ApplicationDidFinishInitializationNotification, this));
	}

	/**
	 * Notification method called when the application posts the notification
	 * {@link WOApplication#ApplicationDidFinishLaunchingNotification}. This
	 * method calls subclasse's {@link #didFinishLaunching} method.
	 * 
	 * @param n
	 *            notification that is posted after the WOApplication has
	 *            finished launching and is ready for accepting requests.
	 */
	public final void didFinishLaunching(NSNotification n) {
		didFinishLaunching();
		ERXStats.logStatisticsForOperation(statsLog, "sum");

		if (isDevelopmentMode() && !autoOpenInBrowser()) {
			log.warn("You are running in development mode with WOAutoOpenInBrowser = false.  No browser will open and it will look like the application is hung, but it's not.  There's just not a browser opening automatically.");
		}
		
		// FIXME: Is this being handled by ERXStats? Check out. 
		_startupTimeInMilliseconds = System.currentTimeMillis() - _startupTimeInMilliseconds;
		log.info( String.format( "Startup time %s ms: ", _startupTimeInMilliseconds ) );
	}

	/**
	 * Called when the application posts
	 * {@link WOApplication#ApplicationWillFinishLaunchingNotification}.
	 * Override this to perform application initialization. (optional)
	 */
	public void finishInitialization() {
		// empty
	}

	protected void didCreateApplication() {
		// empty
	}

	/**
	 * Called when the application posts
	 * {@link WOApplication#ApplicationDidFinishLaunchingNotification}. Override
	 * this to perform application specific tasks after the application has been
	 * initialized. THis is a good spot to perform batch application tasks.
	 */
	public void didFinishLaunching() {
	}

	/**
	 * The ERXApplication singleton.
	 * 
	 * @return returns the <code>WOApplication.application()</code> cast as an
	 *         ERXApplication
	 */
	public static ERXApplication erxApplication() {
		return (ERXApplication) WOApplication.application();
	}

	/**
	 * Adds support for automatic application cycling. Applications can be
	 * configured to cycle in two ways:
	 * <p>
	 * The first way is by setting the System property <b>ERTimeToLive</b> to
	 * the number of seconds (+ a random interval of 10 minutes) that the
	 * application should be up before terminating. Note that when the
	 * application's time to live is up it will quit calling the method
	 * <code>killInstance</code>.
	 * <p>
	 * The second way is by setting the System property <b>ERTimeToDie</b> to
	 * the time in seconds after midnight when the app should be starting to
	 * refuse new sessions. In this case when the application starts to refuse
	 * new sessions it will also register a kill timer that will terminate the
	 * application between 0 minutes and 1:00 minutes.
	 */
	@Override
	public void run() {
		try {
			int timeToLive = ERXProperties.intForKey("ERTimeToLive");
			if (timeToLive > 0) {
				log.info("Instance will live " + timeToLive + " seconds.");
				NSLog.out.appendln("Instance will live " + timeToLive + " seconds.");
				// add a fudge factor of around 10 minutes
				timeToLive += Math.random() * 600;
				NSTimestamp exitDate = (new NSTimestamp()).timestampByAddingGregorianUnits(0, 0, 0, 0, 0, timeToLive);
				WOTimer t = new WOTimer(exitDate, 0, this, "killInstance", null, null, false);
				t.schedule();
			}
			int timeToDie = ERXProperties.intForKey("ERTimeToDie");
			if (timeToDie > 0) {
				log.info("Instance will not live past " + timeToDie + ":00.");
				NSLog.out.appendln("Instance will not live past " + timeToDie + ":00.");

				LocalDateTime now = LocalDateTime.now();

				int s = (timeToDie - now.getHour()) * 3600 - now.getMinute() * 60;

				if (s < 0) {
					s += 24 * 3600; // how many seconds to the deadline
				}

				// deliberately randomize this so that not all instances restart
				// at
				// the same time
				// adding up to 1 hour
				s += (Math.random() * 3600);

				NSTimestamp stopDate = new NSTimestamp().timestampByAddingGregorianUnits(0, 0, 0, 0, 0, s);

				WOTimer t = new WOTimer(stopDate, 0, this, "startRefusingSessions", null, null, false);
				t.schedule();
			}
			super.run();
		}
		catch (RuntimeException t) {
			if (ERXApplication._wasMainInvoked) {
				ERXApplication.log.error(name() + " failed to start.", t);
				// throw new ERXExceptionUtilities.HideStackTraceException(t);
			}
			throw t;
		}
	}

	@Override
	public ERXRequest createRequest(String aMethod, String aURL, String anHTTPVersion, Map<String, ? extends List<String>> someHeaders, NSData aContent, Map<String, Object> someInfo) {
		// Workaround for #3428067 (Apache Server Side Include module will feed
		// "INCLUDED" as the HTTP version, which causes a request object not to
		// be created by an exception.
		if (anHTTPVersion == null || anHTTPVersion.startsWith("INCLUDED")) {
			anHTTPVersion = "HTTP/1.0";
		}

		// Workaround for Safari on Leopard bug (post followed by redirect to
		// GET incorrectly has content-type header).
		// The content-type header makes the WO parser only look at the content.
		// Which is empty.
		// http://lists.macosforge.org/pipermail/webkit-unassigned/2007-November/053847.html
		// http://jira.atlassian.com/browse/JRA-13791
		if ("GET".equalsIgnoreCase(aMethod) && someHeaders != null && someHeaders.get("content-type") != null) {
			someHeaders.remove("content-type");
		}

		if (rewriteDirectConnectURL()) {
			aURL = adaptorPath() + name() + applicationExtension() + aURL;
		}

		return new ERXRequest(aMethod, aURL, anHTTPVersion, someHeaders, aContent, someInfo);
	}

	/**
	 * Used to instantiate a WOComponent when no context is available, typically
	 * outside of a session
	 * 
	 * @param pageName
	 *            - The name of the WOComponent that must be instantiated.
	 * @return created WOComponent with the given name
	 */
	public static WOComponent instantiatePage(String pageName) {
		WOContext context = ERXWOContext.newContext();
		return application().pageWithName(pageName, context);
	}

	/**
	 * Stops the application from handling any new requests. Will still handle
	 * requests from existing sessions.
	 */
	public void startRefusingSessions() {
		log.info("Refusing new sessions");
		NSLog.out.appendln("Refusing new sessions");
		refuseNewSessions(true);
	}

	protected WOTimer _killTimer;

	/**
	 * Bugfix for WO component loading. It fixes:
	 * <ul>
	 * <li>when isCachingEnabled is ON, and you have a new browser language that
	 * hasn't been seen so far, the component gets re-read from the disk, which
	 * can wreak havoc if you overwrite your html/wod with a new version.
	 * <li>when caching enabled is OFF, and you make a change, you only see the
	 * change in the first browser that touches the page. You need to re-save if
	 * you want it seen in the second one.
	 * </ul>
	 * You need to set
	 * <code>er.extensions.ERXApplication.fixCachingEnabled=false</code> is you
	 * don't want it to load.
	 * 
	 * @author ak
	 */
	@Override
	public WOComponentDefinition _componentDefinition(String s, NSArray nsarray) {
		if (ERXProperties.booleanForKeyWithDefault("er.extensions.ERXApplication.fixCachingEnabled", true)) {
			// _expectedLanguages already contains all the languages in all
			// projects, so
			// there is no need to check for the ones that come in...
			return super._componentDefinition(s, (nsarray != null ? nsarray.arrayByAddingObjectsFromArray(_expectedLanguages()) : _expectedLanguages()));
		}
		return super._componentDefinition(s, nsarray);
	}

	private boolean _isMemoryLow = false;
	private boolean _isMemoryStarved = false;

	/**
	 * <p>
	 * Checks if the free memory is less than the threshold given in
	 * <code>er.extensions.ERXApplication.memoryStarvedThreshold</code> (should
	 * be set to around 0.90 meaning 90% of total memory or 100 meaning 100 MB
	 * of minimal available memory) and if it is greater start to refuse new
	 * sessions until more memory becomes available. This helps when the
	 * application is becoming unresponsive because it's more busy garbage
	 * collecting than processing requests. The default is to do nothing unless
	 * the property is set. This method is called on each request, but garbage
	 * collection will be done only every minute.
	 * </p>
	 * 
	 * <p>
	 * Additionally, you can set
	 * <code>er.extensions.ERXApplication.memoryLowThreshold</code>, which you
	 * can set at a higher "warning" level, before the situation is critical.
	 * </p>
	 * 
	 * <p>
	 * Both of these methods post notifications both at the start of the event
	 * as well as the end of the event
	 * (LowMemoryNotification/LowMemoryResolvedNotification and
	 * StarvedMemoryNotification and StarvedMemoryResolvedNotification).
	 * </p>
	 * 
	 * @author ak
	 */
	private void checkMemory() {
		boolean memoryLow = checkMemory(_memoryLowThreshold, false);
		if (memoryLow != _isMemoryLow) {
			if (!memoryLow) {
				log.warn("App is no longer low on memory");
				NSNotificationCenter.defaultCenter().postNotification(new NSNotification(LowMemoryResolvedNotification, this));
			}
			else {
				log.error("App is low on memory");
				NSNotificationCenter.defaultCenter().postNotification(new NSNotification(LowMemoryNotification, this));
			}
			_isMemoryLow = memoryLow;
		}

		boolean memoryStarved = checkMemory(_memoryStarvedThreshold, true);
		if (memoryStarved != _isMemoryStarved) {
			if (!memoryStarved) {
				log.warn("App is no longer starved, handling new sessions again");
				NSNotificationCenter.defaultCenter().postNotification(new NSNotification(StarvedMemoryResolvedNotification, this));
			}
			else {
				log.error("App is starved, starting to refuse new sessions");
				NSNotificationCenter.defaultCenter().postNotification(new NSNotification(StarvedMemoryNotification, this));
			}
			_isMemoryStarved = memoryStarved;
		}
	}

	protected boolean checkMemory(BigDecimal memoryThreshold, boolean attemptGC) {
		boolean pastThreshold = false;
		if (memoryThreshold != null) {
			long max = Runtime.getRuntime().maxMemory();
			long total = Runtime.getRuntime().totalMemory();
			long free = Runtime.getRuntime().freeMemory() + (max - total);
			long used = max - free;
			long starvedThreshold = (long) (memoryThreshold.doubleValue() < 1.0 ? memoryThreshold.doubleValue() * max : (max - (memoryThreshold.doubleValue() * 1024 * 1024)));

			synchronized (this) {
				long time = System.currentTimeMillis();
				if (attemptGC && (used > starvedThreshold) && (time > _lastGC + 60 * 1000L)) {
					_lastGC = time;
					Runtime.getRuntime().gc();
					max = Runtime.getRuntime().maxMemory();
					total = Runtime.getRuntime().totalMemory();
					free = Runtime.getRuntime().freeMemory() + (max - total);
					used = max - free;
				}
				pastThreshold = (used > starvedThreshold);
			}
		}
		return pastThreshold;
	}

	/**
	 * Override and return false if you do not want sessions to be refused when
	 * memory is starved.
	 * 
	 * @return whether or not sessions should be refused on starved memory
	 */
	protected boolean refuseSessionsOnStarvedMemory() {
		return true;
	}

	/**
	 * Overridden to return the super value OR true if the app is memory
	 * starved.
	 */
	@Override
	public boolean isRefusingNewSessions() {
		return super.isRefusingNewSessions() || (refuseSessionsOnStarvedMemory() && _isMemoryStarved);
	}

	/**
	 * Overridden to fix that direct connect apps can't refuse new sessions.
	 */
	@Override
	public synchronized void refuseNewSessions(boolean value) {
		boolean success = false;
		try {
			Field f = WOApplication.class.getDeclaredField("_refusingNewClients");
			f.setAccessible(true);
			f.set(this, value);
			success = true;
		}
		catch (SecurityException e) {
			log.error(e, e);
		}
		catch (NoSuchFieldException e) {
			log.error(e, e);
		}
		catch (IllegalArgumentException e) {
			log.error(e, e);
		}
		catch (IllegalAccessException e) {
			log.error(e, e);
		}
		if (!success) {
			super.refuseNewSessions(value);
		}
		// #81712. App will terminate immediately if the right conditions are
		// met.
		if (value && (activeSessionsCount() <= minimumActiveSessionsCount())) {
			log.info("Refusing new clients and below min active session threshold, about to terminate...");
			terminate();
		}
		resetKillTimer(isRefusingNewSessions());
	}

	/**
	 * Sets the kill timer.
	 * 
	 * @param install
	 */
	private void resetKillTimer(boolean install) {
		// we assume that we changed our mind about killing the instance.
		if (_killTimer != null) {
			_killTimer.invalidate();
			_killTimer = null;
		}
		if (install) {
			int timeToKill = ERXProperties.intForKey("ERTimeToKill");
			if (timeToKill > 0) {
				log.warn("Registering kill timer in " + timeToKill + "seconds");
				NSTimestamp exitDate = (new NSTimestamp()).timestampByAddingGregorianUnits(0, 0, 0, 0, 0, timeToKill);
				_killTimer = new WOTimer(exitDate, 0, this, "killInstance", null, null, false);
				_killTimer.schedule();
			}
		}
	}

	/**
	 * Killing the instance will log a 'Forcing exit' message and then call
	 * <code>System.exit(1)</code>
	 */
	public void killInstance() {
		log.info("Forcing exit");
		NSLog.out.appendln("Forcing exit");
		System.exit(1);
	}

	/**
	 * The name suffix is appended to the current name of the application. This
	 * adds the ability to add a useful suffix to differentiate between
	 * different sets of applications on the same machine.
	 * <p>
	 * The name suffix is set via the System property
	 * <b>ERApplicationNameSuffix</b>. For example if the name of an application
	 * is Buyer and you want to have a training instance appear with the name
	 * BuyerTraining then you would set the ERApplicationNameSuffix to Training.
	 * 
	 * @return the System property <b>ERApplicationNameSuffix</b> or
	 *         <code>""</code>
	 */
	public String nameSuffix() {
		return ERXProperties.stringForKeyWithDefault("ERApplicationNameSuffix", "");
	}

	/** cached computed name */
	private String _userDefaultName;

	/**
	 * Adds the ability to completely change the applications name by setting
	 * the System property <b>ERApplicationName</b>. Will also append the
	 * <code>nameSuffix</code> if one is set.
	 * 
	 * @return the computed name of the application.
	 */
	@Override
	public String name() {
		if (_userDefaultName == null) {
			synchronized (this) {
				_userDefaultName = System.getProperty("ERApplicationName");
				if (_userDefaultName == null)
					_userDefaultName = super.name();
				if (_userDefaultName != null) {
					String suffix = nameSuffix();
					if (suffix != null && suffix.length() > 0)
						_userDefaultName += suffix;
				}
			}
		}
		return _userDefaultName;
	}

	/**
	 * This method returns {@link WOApplication}'s <code>name</code> method.
	 * 
	 * @return the name of the application executable.
	 */
	public String rawName() {
		return super.name();
	}

	/**
	 * Puts together a dictionary with a bunch of useful information relative to
	 * the current state when the exception occurred. Potentially added
	 * information:
	 * <ol>
	 * <li>the current page name</li>
	 * <li>the current component</li>
	 * <li>the complete hierarchy of nested components</li>
	 * <li>the requested uri</li>
	 * <li>the D2W page configuration</li>
	 * <li>the previous page list (from the WOStatisticsStore)</li>
	 * </ol>
	 * Also, in case the top-level exception was a EOGeneralAdaptorException,
	 * then you also get the failed ops and the sql exception.
	 * 
	 * @param e
	 *            exception
	 * @param context
	 *            the current context
	 * @return dictionary containing extra information for the current context.
	 */
	public NSMutableDictionary extraInformationForExceptionInContext(Exception e, WOContext context) {
		NSMutableDictionary<String, Object> extraInfo = new NSMutableDictionary<>();
		extraInfo.addEntriesFromDictionary(ERXRuntimeUtilities.informationForContext(context));
		extraInfo.addEntriesFromDictionary(ERXRuntimeUtilities.informationForBundles());
		return extraInfo;
	}

	/**
	 * Workaround for WO 5.2 DirectAction lock-ups. As the super-implementation
	 * is empty, it is fairly safe to override here to call the normal exception
	 * handling earlier than usual.
	 * 
	 * @see WOApplication#handleActionRequestError(WORequest, Exception, String,
	 *      WORequestHandler, String, String, Class, WOAction)
	 */
	// NOTE: if you use WO 5.1, comment out this method, otherwise it won't
	// compile.
	// CHECKME this was created for WO 5.2, do we still need this for 5.4.3?
	@Override
	public WOResponse handleActionRequestError(WORequest aRequest, Exception exception, String reason, WORequestHandler aHandler, String actionClassName, String actionName, Class actionClass, WOAction actionInstance) {
		WOContext context = actionInstance != null ? actionInstance.context() : null;
		boolean didCreateContext = false;
		if (context == null) {
			// AK: we provide the "handleException" with not much enough info to
			// output a reasonable error message
			context = createContextForRequest(aRequest);
			didCreateContext = true;
		}
		WOResponse response = handleException(exception, context);

		// CH: If we have created a context, then the request handler won't know
		// about it and can't put the components
		// from handleException(exception, context) to sleep nor check-in any
		// session that may have been checked out
		// or created (e.g. from a component action URL.
		//
		// I'm not sure if the reasoning below was valid, or of the real cause
		// of this deadlocking was creating the context
		// above and then creating / checking out a session during
		// handleException(exception, context). In any case, a zombie
		// session was getting created with WO 5.4.3 and this does NOT happen
		// with a pure WO application making the code above
		// a prime suspect. I am leaving the code below in so that if it does
		// something for prior versions, that will still work.
		if (didCreateContext) {
			context._putAwakeComponentsToSleep();
			saveSessionForContext(context);
		}

		// AK: bugfix for #4186886 (Session store deadlock with DAs). The bug
		// occurs in 5.2.3, I'm not sure about other
		// versions.
		// It may create other problems, but this one is very severe to begin
		// with
		// The crux of the matter is that for certain exceptions, the DA request
		// handler does not check sessions back in
		// which leads to a deadlock in the session store when the session is
		// accessed again.
		else if (context.hasSession() && ("InstantiationError".equals(reason) || "InvocationError".equals(reason))) {
			context._putAwakeComponentsToSleep();
			saveSessionForContext(context);
		}
		return response;
	}

	/**
	 * Logs extra information about the current state.
	 * 
	 * @param exception
	 *            to be handled
	 * @param context
	 *            current context
	 * @return the WOResponse of the generated exception page.
	 */
	@Override
	public WOResponse handleException(Exception exception, WOContext context) {
		// We first want to test if we ran out of memory. If so we need to quit ASAP.
		handlePotentiallyFatalException(exception);

		// Not a fatal exception, business as usual.
		final NSDictionary extraInfo = extraInformationForExceptionInContext(exception, context);
		log.error("Exception caught: " + exception.getMessage() + "\nExtra info: " + NSPropertyListSerialization.stringFromPropertyList(extraInfo) + "\n", exception);
		return super.handleException(exception, context);
	}

	/**
	 * Standard exception page. Also logs error to standard out.
	 * 
	 * @param exception
	 *            to be handled
	 * @param context
	 *            current context
	 * @return the WOResponse of the generic exception page.
	 * 
	 * FIXME: Why does this method exist? It's not invoked from anywhere // Hugi 2021-05-30
	 */
	public WOResponse genericHandleException(Exception exception, WOContext context) {
		return super.handleException(exception, context);
	}

	/**
	 * Handles the potentially fatal OutOfMemoryError by quitting the
	 * application ASAP. Broken out into a separate method to make custom error
	 * handling easier, ie. generating your own error pages in production, etc.
	 * 
	 * @param exception
	 *            to check if it is a fatal exception.
	 */
	public void handlePotentiallyFatalException(Exception exception) {
		Throwable throwable = ERXRuntimeUtilities.originalThrowable(exception);
		if (throwable instanceof Error) {
			boolean shouldQuit = false;
			if (throwable instanceof OutOfMemoryError) {
				boolean shouldExitOnOOMError = ERXProperties.booleanForKeyWithDefault(AppShouldExitOnOutOfMemoryError, true);
				shouldQuit = shouldExitOnOOMError;
				// AK: I'm not sure this actually works, in particular when the
				// buffer is in the long-running generational mem, but it's
				// worth a try.
				// what we do is set up a last-resort buffer during startup
				if (lowMemBuffer != null) {
					Runtime.getRuntime().freeMemory();
					try {
						lowMemBuffer = null;
						System.gc();
						log.error("Ran out of memory, sending notification to clear caches");
						log.error("Ran out of memory, sending notification to clear caches", throwable);
						NSNotificationCenter.defaultCenter().postNotification(new NSNotification(LowMemoryNotification, this));
						shouldQuit = false;
						// try to reclaim our twice of our buffer
						// if this worked maybe we can continue running
						lowMemBuffer = new byte[lowMemBufferSize * 2];
						// shrink buffer to normal size
						lowMemBuffer = new byte[lowMemBufferSize];
					}
					catch (Throwable ex) {
						shouldQuit = shouldExitOnOOMError;
					}
				}
				// We first log just in case the log4j call puts us in a bad
				// state.
				if (shouldQuit) {
					NSLog.err.appendln("Ran out of memory, killing this instance");
					log.fatal("Ran out of memory, killing this instance");
					log.fatal("Ran out of memory, killing this instance", throwable);
				}
			}
			else {
				// We log just in case the log4j call puts us in a bad
				// state.
				NSLog.err.appendln("java.lang.Error \"" + throwable.getClass().getName() + "\" occured.");
				log.error("java.lang.Error \"" + throwable.getClass().getName() + "\" occured.", throwable);
			}
			if (shouldQuit)
				Runtime.getRuntime().exit(1);
		}
	}

	/**
	 * Dispatches the request without checking for the delayedRequestHandler()
	 * 
	 * @param request
	 */
	public WOResponse dispatchRequest(WORequest request) {
		WOResponse response;

		if (ERXApplication.requestHandlingLog.isDebugEnabled()) {
			ERXApplication.requestHandlingLog.debug(request);
		}

		try {
			ERXStats.initStatisticsIfNecessary();
			checkMemory();
			response = super.dispatchRequest(request);
		}
		finally {
			ERXStats.logStatisticsForOperation(statsLog, "key");
			ERXThreadStorage.reset();
		}

		if (requestHandlingLog.isDebugEnabled()) {
			requestHandlingLog.debug("Returning, encoding: " + response.contentEncoding() + " response: " + response);
		}

		if( ERXResponseCompression.responseCompressionEnabled() ) {
			if( ERXResponseCompression.shouldCompress( request, response ) ) {
				response = ERXResponseCompression.compressResponse( response );
			}
		}

		return response;
	}

	/**
	 * When a context is created we push it into thread local storage. This
	 * handles the case for direct actions.
	 * 
	 * @param request
	 *            the request
	 * @return the newly created context
	 */
	@Override
	public WOContext createContextForRequest(WORequest request) {
		WOContext context = super.createContextForRequest(request);
		// We only want to push in the context the first time it is
		// created, ie we don't want to lose the current context
		// when we create a context for an error page.
		if (ERXWOContext.currentContext() == null) {
			ERXWOContext.setCurrentContext(context);
		}
		return context;
	}

	@Override
	public WOResponse createResponseInContext(WOContext context) {
		WOResponse response = new ERXResponse(context);
		return response;
	}

	/**
	 * Logs the warning message if the main method was not called during the
	 * startup.
	 */
	private void _displayMainMethodWarning() {
		log.warn("\n\nIt seems that your application class " + application().getClass().getName() + " did not call " + ERXApplication.class.getName() + ".main(argv[], applicationClass) method. " + "Please modify your Application.java as the followings so that " + ERXConfigurationManager.class.getName() + " can provide its " + "rapid turnaround feature completely. \n\n" + "Please change Application.java like this: \n" + "public static void main(String argv[]) { \n" + "    ERXApplication.main(argv, Application.class); \n" + "}\n\n");
	}

	/** improved streaming support */
	protected NSMutableArray<String> _streamingRequestHandlerKeys = new NSMutableArray<>(streamActionRequestHandlerKey());

	public void registerStreamingRequestHandlerKey(String s) {
		if (!_streamingRequestHandlerKeys.containsObject(s)) {
			_streamingRequestHandlerKeys.addObject(s);
		}
	}

	public boolean isStreamingRequestHandlerKey(String s) {
		return _streamingRequestHandlerKeys.containsObject(s);
	}

	/** use the redirect feature */
	protected Boolean _useSessionStoreDeadlockDetection;

	/**
	 * Deadlock in session-store detection. Note that the detection only work in
	 * single-threaded mode, and is mostly useful to find cases when a session
	 * is checked out twice in a single RR-loop, which will lead to a session
	 * store lockup. Set the
	 * <code>er.extensions.ERXApplication.useSessionStoreDeadlockDetection=true</code>
	 * property to actually the this feature.
	 * 
	 * @return flag if to use the this feature
	 */
	public boolean useSessionStoreDeadlockDetection() {
		if (_useSessionStoreDeadlockDetection == null) {
			_useSessionStoreDeadlockDetection = ERXProperties.booleanForKey("er.extensions.ERXApplication.useSessionStoreDeadlockDetection") ? Boolean.TRUE : Boolean.FALSE;
			if (isConcurrentRequestHandlingEnabled() && _useSessionStoreDeadlockDetection.booleanValue()) {
				log.error("Sorry, useSessionStoreDeadlockDetection does not work with concurrent request handling enabled.");
				_useSessionStoreDeadlockDetection = Boolean.FALSE;
			}
		}
		return _useSessionStoreDeadlockDetection.booleanValue();
	}

	/**
	 * Returns whether or not this application is in development mode. This one
	 * is named "Safe" because it does not require you to be running an
	 * ERXApplication (and because you can't have a static and not-static method
	 * of the same name. bah). If you are using ERXApplication, this will call
	 * isDevelopmentMode on your application. If not, it will call
	 * ERXApplication_defaultIsDevelopmentMode() which checks for the system
	 * properties "er.extensions.ERXApplication.developmentMode" and/or "WOIDE".
	 * 
	 * @return whether or not the current application is in development mode
	 */
	public static boolean isDevelopmentModeSafe() {
		boolean developmentMode;
		WOApplication application = WOApplication.application();
		if (application instanceof ERXApplication) {
			ERXApplication erxApplication = (ERXApplication) application;
			developmentMode = erxApplication.isDevelopmentMode();
		}
		else {
			developmentMode = ERXApplication._defaultIsDevelopmentMode();
		}
		return developmentMode;
	}

	/**
	 * Returns whether or not this application is running in development-mode.
	 * If you are using Xcode, you should add a WOIDE=Xcode setting to your
	 * launch parameters.
	 */
	protected static boolean _defaultIsDevelopmentMode() {
		boolean developmentMode = false;
		if (ERXProperties.stringForKey("er.extensions.ERXApplication.developmentMode") != null) {
			developmentMode = ERXProperties.booleanForKey("er.extensions.ERXApplication.developmentMode");
		}
		else {
			String ide = ERXProperties.stringForKey("WOIDE");
			if ("WOLips".equals(ide) || "Xcode".equals(ide)) {
				developmentMode = true;
			}
			if (!developmentMode) {
				developmentMode = ERXProperties.booleanForKey("NSProjectBundleEnabled");
			}
		}
		// AK: these are for quickly uncommenting while testing
		// if(true) return false;
		// if(true) return true;
		return developmentMode;
	}

	/**
	 * Returns whether or not this application is running in development-mode.
	 * If you are using Xcode, you should add a WOIDE=Xcode setting to your
	 * launch parameters.
	 * 
	 * @return <code>true</code> if application is in dev mode
	 */
	public boolean isDevelopmentMode() {
		return ERXApplication._defaultIsDevelopmentMode();
	}

	/** holds the info on checked-out sessions */
	private Map<String, SessionInfo> _sessions = new HashMap<>();

	/** Holds info about where and who checked out */
	private class SessionInfo {
		Exception _trace = new Exception();
		WOContext _context;

		public SessionInfo(WOContext wocontext) {
			_context = wocontext;
		}

		public Exception trace() {
			return _trace;
		}

		public WOContext context() {
			return _context;
		}

		public String exceptionMessageForCheckout(WOContext wocontext) {
			String contextDescription = null;
			if (_context != null) {
				contextDescription = "contextId: " + _context.contextID() + " request: " + _context.request();
			}
			else {
				contextDescription = "<NULL>";
			}

			log.error("There is an error in the session check-out: old context: " + contextDescription, trace());
			if (_context == null) {
				return "Original context was null";
			}
			else if (_context.equals(wocontext)) {
				return "Same context did check out twice";
			}
			else {
				return "Context with id '" + wocontext.contextID() + "' did check out again";
			}
		}
	}

	/** Overridden to check the sessions */
	@Override
	public WOSession createSessionForRequest(WORequest worequest) {
		WOSession wosession = super.createSessionForRequest(worequest);
		if (wosession != null && useSessionStoreDeadlockDetection()) {
			_sessions.put(wosession.sessionID(), new SessionInfo(null));
		}
		return wosession;
	}

	/** Overridden to check the sessions */
	@Override
	public void saveSessionForContext(WOContext wocontext) {
		if (useSessionStoreDeadlockDetection()) {
			WOSession wosession = wocontext._session();
			if (wosession != null) {
				String sessionID = wosession.sessionID();
				SessionInfo sessionInfo = _sessions.get(sessionID);
				if (sessionInfo == null) {
					log.error("Check-In of session that was not checked out, most likely diue to an exception in session.awake(): " + sessionID);
				}
				else {
					_sessions.remove(sessionID);
				}
			}
		}
		super.saveSessionForContext(wocontext);
	}

	/** Overridden to check the sessions */
	@Override
	public WOSession restoreSessionWithID(String sessionID, WOContext wocontext) {
		if (sessionID != null && ERXSession.session() != null && sessionID.equals(ERXSession.session().sessionID())) {
			// AK: I have no idea how this can happen
			throw new IllegalStateException("Trying to check out a session twice in one RR loop: " + sessionID);
		}
		WOSession session = null;
		if (useSessionStoreDeadlockDetection()) {
			SessionInfo sessionInfo = _sessions.get(sessionID);
			if (sessionInfo != null) {
				throw new IllegalStateException(sessionInfo.exceptionMessageForCheckout(wocontext));
			}
			session = super.restoreSessionWithID(sessionID, wocontext);
			if (session != null) {
				_sessions.put(session.sessionID(), new SessionInfo(wocontext));
			}
		}
		else {
			session = super.restoreSessionWithID(sessionID, wocontext);
		}
		return session;
	}

	public Number sessionTimeOutInMinutes() {
		return Integer.valueOf(sessionTimeOut().intValue() / 60);
	}

	/**
	 * This method is called by ERXWOContext and provides the application a hook
	 * to rewrite generated URLs.
	 * 
	 * You can also set "er.extensions.replaceApplicationPath.pattern" to the
	 * pattern to match and "er.extensions.replaceApplicationPath.replace" to
	 * the value to replace it with.
	 * 
	 * For example, in Properties: <code>
	 * er.extensions.ERXApplication.replaceApplicationPath.pattern=/cgi-bin/WebObjects/YourApp.woa
	 * er.extensions.ERXApplication.replaceApplicationPath.replace=/yourapp
	 * </code>
	 * 
	 * and in Apache 2.2: <code>
	 * RewriteRule ^/yourapp(.*)$ /cgi-bin/WebObjects/YourApp.woa$1 [PT,L]
	 * </code>
	 * 
	 * or Apache 1.3: <code>
	 * RewriteRule ^/yourapp(.*)$ /cgi-bin/WebObjects/YourApp.woa$1 [P,L]
	 * </code>
	 *
	 * @param url
	 *            the URL to rewrite
	 * @return the rewritten URL
	 */
	public String _rewriteURL(String url) {
		String processedURL = url;
		if (url != null && _replaceApplicationPathPattern != null && _replaceApplicationPathReplace != null) {
			processedURL = processedURL.replaceFirst(_replaceApplicationPathPattern, _replaceApplicationPathReplace);
		}
		return processedURL;
	}

	/**
	 * This method is called by ERXResourceManager and provides the application
	 * a hook to rewrite generated URLs for resources.
	 *
	 * @param url
	 *            the URL to rewrite
	 * @param bundle
	 *            the bundle the resource is located in
	 * @return the rewritten URL
	 */
	public String _rewriteResourceURL(String url, WODeployedBundle bundle) {
		return url;
	}

	/**
	 * Returns whether or not to rewrite direct connect URLs.
	 * 
	 * @return whether or not to rewrite direct connect URLs
	 */
	public boolean rewriteDirectConnectURL() {
		return isDirectConnectEnabled() && !isCachingEnabled() && isDevelopmentMode() && ERXProperties.booleanForKeyWithDefault("er.extensions.ERXApplication.rewriteDirectConnect", false);
	}

	/**
	 * Returns the directConnecURL, optionally rewritten.
	 */
	@Override
	public String directConnectURL() {
		String directConnectURL = super.directConnectURL();
		if (rewriteDirectConnectURL()) {
			directConnectURL = _rewriteURL(directConnectURL);
		}
		return directConnectURL;
	}

	/**
	 * Set the default encoding of the app (message encodings)
	 * 
	 * @param encoding
	 */
	public void setDefaultEncoding(String encoding) {
		WOMessage.setDefaultEncoding(encoding);
		WOMessage.setDefaultURLEncoding(encoding);
		ERXMessageEncoding.setDefaultEncoding(encoding);
		ERXMessageEncoding.setDefaultEncodingForAllLanguages(encoding);
	}

	/**
	 * Returns the component for the given class without having to cast. For
	 * example: MyPage page =
	 * ERXApplication.erxApplication().pageWithName(MyPage.class, context);
	 * 
	 * @param <T>
	 *            the type of component to
	 * @param componentClass
	 *            the component class to lookup
	 * @param context
	 *            the context
	 * @return the created component
	 */
	@SuppressWarnings("unchecked")
	public <T extends WOComponent> T pageWithName(Class<T> componentClass, WOContext context) {
		return (T) super.pageWithName(componentClass.getName(), context);
	}

	/**
	 * Calls pageWithName with ERXWOContext.currentContext() for the current
	 * thread.
	 * 
	 * @param <T>
	 *            the type of component to
	 * @param componentClass
	 *            the component class to lookup
	 * @return the created component
	 */
	@SuppressWarnings("unchecked")
	public <T extends WOComponent> T pageWithName(Class<T> componentClass) {
		return (T) pageWithName(componentClass.getName(), ERXWOContext.currentContext());
	}

	/**
	 * Returns whether or not DirectConnect SSL should be enabled. If you set
	 * this, please review the DirectConnect SSL section of the ERExtensions
	 * sample Properties file to learn more about how to properly configure it.
	 * 
	 * @return whether or not DirectConnect SSL should be enabled
	 * @property er.extensions.ERXApplication.ssl.enabled
	 */
	public boolean sslEnabled() {
		return ERXProperties.booleanForKey("er.extensions.ERXApplication.ssl.enabled");
	}

	/**
	 * Returns the host name that will be used to bind the SSL socket to
	 * (defaults to host()).
	 * 
	 * @return the SSL socket host
	 * @property er.extensions.ERXApplication.ssl.host
	 */
	public String sslHost() {
		String sslHost = _sslHost;
		if (sslHost == null) {
			sslHost = ERXProperties.stringForKeyWithDefault("er.extensions.ERXApplication.ssl.host", host());
		}
		return sslHost;
	}

	/**
	 * Sets an SSL host override.
	 * 
	 * @param sslHost
	 *            an SSL host override
	 */
	public void _setSslHost(String sslHost) {
		_sslHost = sslHost;
	}

	/**
	 * Returns the SSL port that will be used for DirectConnect SSL (defaults to
	 * 443). A value of 0 will cause WO to autogenerate an SSL port number.
	 * 
	 * @return the SSL port that will be used for DirectConnect SSL
	 * @property er.extensions.ERXApplication.ssl.port
	 */
	public int sslPort() {
		int sslPort;
		if (_sslPort != null) {
			sslPort = _sslPort.intValue();
		}
		else {
			sslPort = ERXProperties.intForKeyWithDefault("er.extensions.ERXApplication.ssl.port", 443);
		}
		return sslPort;
	}

	/**
	 * Sets an SSL port override (called back by the ERXSecureAdaptor)
	 * 
	 * @param sslPort
	 *            an ssl port override
	 */
	public void _setSslPort(int sslPort) {
		_sslPort = sslPort;
	}

	/**
	 * Injects additional adaptors into the WOAdditionalAdaptors setting.
	 * Subclasses can extend this method, but should call
	 * super._addAdditionalAdaptors.
	 * 
	 * @param additionalAdaptors
	 *            the mutable adaptors array
	 */
	protected void _addAdditionalAdaptors(NSMutableArray<NSDictionary<String, Object>> additionalAdaptors) {
		if (sslEnabled()) {
			boolean sslAdaptorConfigured = false;
			for (NSDictionary<String, Object> adaptor : additionalAdaptors) {
				if (ERXSecureDefaultAdaptor.class.getName().equals(adaptor.objectForKey(WOProperties._AdaptorKey))) {
					sslAdaptorConfigured = true;
				}
			}
			ERXSecureDefaultAdaptor.checkSSLConfig();
			if (!sslAdaptorConfigured) {
				NSMutableDictionary<String, Object> sslAdaptor = new NSMutableDictionary<>();
				sslAdaptor.setObjectForKey(ERXSecureDefaultAdaptor.class.getName(), WOProperties._AdaptorKey);
				String sslHost = sslHost();
				if (sslHost != null) {
					sslAdaptor.setObjectForKey(sslHost, WOProperties._HostKey);
				}
				sslAdaptor.setObjectForKey(Integer.valueOf(sslPort()), WOProperties._PortKey);
				additionalAdaptors.addObject(sslAdaptor);
			}
		}
	}

	/**
	 * Returns the additionalAdaptors, but calls _addAdditionalAdaptors to give
	 * the runtime an opportunity to programmatically force adaptors into the
	 * list.
	 */
	@Override
	@SuppressWarnings("deprecation")
	public NSArray<NSDictionary<String, Object>> additionalAdaptors() {
		NSArray<NSDictionary<String, Object>> additionalAdaptors = super.additionalAdaptors();
		if (!_initializedAdaptors) {
			NSMutableArray<NSDictionary<String, Object>> mutableAdditionalAdaptors = additionalAdaptors.mutableClone();
			_addAdditionalAdaptors(mutableAdditionalAdaptors);
			_initializedAdaptors = true;
			additionalAdaptors = mutableAdditionalAdaptors;
			setAdditionalAdaptors(mutableAdditionalAdaptors);
		}
		return additionalAdaptors;
	}

	/**
	 * Overridden to check for (and optionally kill) an existing running instance on the same port
	 */
	@Override
	public WOAdaptor adaptorWithName(String aClassName, NSDictionary<String, Object> anArgsDictionary) {
		try {
			return super.adaptorWithName(aClassName, anArgsDictionary);
		}
		catch (NSForwardException e) {
			Throwable rootCause = ERXExceptionUtilities.getMeaningfulThrowable(e);
			if ((rootCause instanceof BindException) && stopPreviousDevInstance()) {
				return super.adaptorWithName(aClassName, anArgsDictionary);
			}
			throw e;
		}
	}

	protected void _debugValueForDeclarationNamed(WOComponent component, String verb, String aDeclarationName, String aDeclarationType, String aBindingName, String anAssociationDescription, Object aValue) {
		if (aValue instanceof String) {
			StringBuilder stringbuffer = new StringBuilder(((String) aValue).length() + 2);
			stringbuffer.append('"');
			stringbuffer.append(aValue);
			stringbuffer.append('"');
			aValue = stringbuffer;
		}
		if (aDeclarationName.startsWith("_")) {
			aDeclarationName = "[inline]";
		}

		StringBuilder sb = new StringBuilder();

		// NSArray<WOComponent> componentPath =
		// ERXWOContext._componentPath(ERXWOContext.currentContext());
		// componentPath.lastObject()
		// WOComponent lastComponent =
		// ERXWOContext.currentContext().component();
		String lastComponentName = component.name().replaceFirst(".*\\.", "");
		sb.append(lastComponentName);

		sb.append(verb);

		if (!aDeclarationName.startsWith("_")) {
			sb.append(aDeclarationName);
			sb.append(':');
		}
		sb.append(aDeclarationType);

		sb.append(" { ");
		sb.append(aBindingName);
		sb.append('=');

		String valueStr = aValue != null ? aValue.toString() : "null";
		if (anAssociationDescription.startsWith("class ")) {
			sb.append(valueStr);
			sb.append("; }");
		}
		else {
			sb.append(anAssociationDescription);
			sb.append("; } value ");
			sb.append(valueStr);
		}

		NSLog.debug.appendln(sb.toString());
	}

	/**
	 * The set of component names that have binding debug enabled
	 */
	private NSMutableSet<String> _debugComponents = new NSMutableSet<>();

	/**
	 * Little bit better binding debug output than the original.
	 */
	@Override
	public void logTakeValueForDeclarationNamed(String aDeclarationName, String aDeclarationType, String aBindingName, String anAssociationDescription, Object aValue) {
		WOComponent component = ERXWOContext.currentContext().component();
		if (component.parent() != null) {
			component = component.parent();
		}
		_debugValueForDeclarationNamed(component, " ==> ", aDeclarationName, aDeclarationType, aBindingName, anAssociationDescription, aValue);
	}

	/**
	 * Little bit better binding debug output than the original.
	 */
	@Override
	public void logSetValueForDeclarationNamed(String aDeclarationName, String aDeclarationType, String aBindingName, String anAssociationDescription, Object aValue) {
		WOComponent component = ERXWOContext.currentContext().component();
		if (component.parent() != null) {
			component = component.parent();
		}
		_debugValueForDeclarationNamed(component, " <== ", aDeclarationName, aDeclarationType, aBindingName, anAssociationDescription, aValue);
	}

	/**
	 * Turns on/off binding debugging for the given component. Binding debugging
	 * requires using the WOOgnl template parser and setting
	 * ognl.debugSupport=true.
	 * 
	 * @param debugEnabled
	 *            whether or not to enable debugging
	 * @param componentName
	 *            the component name to enable debugging for
	 */
	public void setDebugEnabledForComponent(boolean debugEnabled, String componentName) {
		if (debugEnabled) {
			_debugComponents.addObject(componentName);
		}
		else {
			_debugComponents.removeObject(componentName);
		}
	}

	/**
	 * Returns whether or not binding debugging is enabled for the given
	 * component
	 * 
	 * @param componentName
	 *            the component name
	 * @return whether or not binding debugging is enabled for the given
	 *         component
	 */
	public boolean debugEnabledForComponent(String componentName) {
		return _debugComponents.containsObject(componentName);
	}

	/**
	 * Turns off binding debugging for all components.
	 */
	public void clearDebugEnabledForAllComponents() {
		_debugComponents.removeAllObjects();
	}

	/**
	 * Sends out a ApplicationWillTerminateNotification before actually starting
	 * to terminate.
	 */
	@Override
	public void terminate() {
		NSNotificationCenter.defaultCenter().postNotification(ApplicationWillTerminateNotification, this);
		super.terminate();
	}

	/**
	 * Override default implementation that returns {".dll", ".exe"} and
	 * therefor prohibits IIS as WebServer.
	 */
	@Override
	public String[] adaptorExtensions() {
		return myAppExtensions;
	}

	public void addBalancerRouteCookieByNotification(NSNotification notification) {
		if (notification.object() instanceof WOContext) {
			addBalancerRouteCookie((WOContext) notification.object());
		}
	}

	public void addBalancerRouteCookie(WOContext context) {
		if (context != null && context.request() != null && context.response() != null) {
			if (_proxyBalancerRoute == null) {
				_proxyBalancerRoute = (name() + "_" + port().toString()).toLowerCase();
				_proxyBalancerRoute = "." + _proxyBalancerRoute.replace('.', '_');
			}
			if (_proxyBalancerCookieName == null) {
				_proxyBalancerCookieName = ("routeid_" + name()).toLowerCase();
				_proxyBalancerCookieName = _proxyBalancerCookieName.replace('.', '_');
			}
			if (_proxyBalancerCookiePath == null) {
				_proxyBalancerCookiePath = (System.getProperty("FixCookiePath") != null) ? System.getProperty("FixCookiePath") : "/";
			}
			WOCookie cookie = new WOCookie(_proxyBalancerCookieName, _proxyBalancerRoute, _proxyBalancerCookiePath, null, -1, context.request().isSecure(), true);
			cookie.setExpires(null);
			context.response().addCookie(cookie);
		}
	}

	public String publicHost() {
		return _publicHost;
	}
}
