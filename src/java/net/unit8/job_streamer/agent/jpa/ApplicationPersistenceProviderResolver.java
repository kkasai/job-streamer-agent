package net.unit8.job_streamer.agent.jpa;

import javax.persistence.spi.PersistenceProviderResolver;

import javax.persistence.PersistenceException;
import javax.persistence.spi.PersistenceProvider;
import javax.persistence.spi.PersistenceProviderResolver;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author kawasima
 */
public class ApplicationPersistenceProviderResolver implements PersistenceProviderResolver {

    /**
     * Cached list of available providers cached by CacheKey to ensure
     * there is not potential for provider visibility issues.
     */
    private volatile HashMap<CacheKey, PersistenceProviderReference> providers = new HashMap<CacheKey, PersistenceProviderReference>();

    /**
     * Queue for reference objects referring to class loaders or persistence providers.
     */
    private static final ReferenceQueue referenceQueue = new ReferenceQueue();

    public List<PersistenceProvider> getPersistenceProviders() {
        // Before we do the real loading work, see whether we need to
        // do some cleanup: If references to class loaders or
        // persistence providers have been nulled out, remove all related
        // information from the cache.
        processQueue();

        System.out.println("ApplicagtionPersistenceProviderResolver: getPersistenceProviders");

        ClassLoader loader = getContextClassLoader();
        CacheKey cacheKey = new CacheKey(loader);
        PersistenceProviderReference providersReferent = this.providers.get(cacheKey);
        List<PersistenceProvider> loadedProviders = null;

        System.out.println("ApplicagtionPersistenceProviderResolver: providersReferent=" + providersReferent);
        if (providersReferent != null) {
            loadedProviders = providersReferent.get();
        }

        if (loadedProviders == null) {
            System.out.println("ApplicagtionPersistenceProviderResolver: loader=" + loader);
            Collection<ProviderName> providerNames = getProviderNames(loader);

            System.out.println("ApplicagtionPersistenceProviderResolver: providerNames=" + providerNames);
            loadedProviders = new ArrayList<PersistenceProvider>();

            for (ProviderName providerName : providerNames) {
                try {
                    PersistenceProvider provider = (PersistenceProvider) loader.loadClass(providerName.getName()).newInstance();
                    loadedProviders.add(provider);
                } catch (ClassNotFoundException cnfe) {
                    log(Level.FINEST, cnfe + ": " + providerName);
                } catch (InstantiationException ie) {
                    log(Level.FINEST, ie + ": " + providerName);
                } catch (IllegalAccessException iae) {
                    log(Level.FINEST, iae + ": " + providerName);
                } catch (ClassCastException cce) {
                    log(Level.FINEST, cce + ": " + providerName);
                }
            }

            // If none are found we'll log the provider names for diagnostic
            // purposes.
            if (loadedProviders.isEmpty() && !providerNames.isEmpty()) {
                log(Level.WARNING, "No valid providers found using:");
                for (ProviderName name : providerNames) {
                    log(Level.WARNING, name.toString());
                }
            }

            providersReferent = new PersistenceProviderReference(loadedProviders, referenceQueue, cacheKey);

            this.providers.put(cacheKey, providersReferent);
        }

        return loadedProviders;
    }

    /**
     * Remove garbage collected cache keys & providers.
     */
    private void processQueue() {
        CacheKeyReference ref;
        while ((ref = (CacheKeyReference) referenceQueue.poll()) != null) {
            providers.remove(ref.getCacheKey());
        }
    }

    /**
     * Wraps <code>Thread.currentThread().getContextClassLoader()</code> into a doPrivileged block if security manager is present
     */
    private static ClassLoader getContextClassLoader() {
        if (System.getSecurityManager() == null) {
            return Thread.currentThread().getContextClassLoader();
        }
        else {
            return  (ClassLoader) java.security.AccessController.doPrivileged(
                    new java.security.PrivilegedAction() {
                        public java.lang.Object run() {
                            return Thread.currentThread().getContextClassLoader();
                        }
                    }
            );
        }
    }


    private static final String LOGGER_SUBSYSTEM = "javax.persistence.spi";

    private Logger logger;

    private void log(Level level, String message) {
        if (this.logger == null) {
            this.logger = Logger.getLogger(LOGGER_SUBSYSTEM);
        }
        this.logger.log(level, LOGGER_SUBSYSTEM + "::" + message);
    }

    private static final String SERVICE_PROVIDER_FILE = "META-INF/services/javax.persistence.spi.PersistenceProvider";

    /**
     * Locate all JPA provider services files and collect all of the
     * provider names available.
     */
    private Collection<ProviderName> getProviderNames(ClassLoader loader) {
        Enumeration<URL> resources = null;

        try {
            resources = loader.getResources(SERVICE_PROVIDER_FILE);
        } catch (IOException ioe) {
            throw new PersistenceException("IOException caught: " + loader + ".getResources(" + SERVICE_PROVIDER_FILE + ")", ioe);
        }

        Collection<ProviderName> providerNames = new ArrayList<ProviderName>();

        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            addProviderNames(url, providerNames);
        }

        return providerNames;
    }

    private static final Pattern nonCommentPattern = Pattern.compile("^([^#]+)");

    /**
     * For each services file look for uncommented provider names on each
     * line.
     */
    private void addProviderNames(URL url, Collection<ProviderName> providerNames) {
        InputStream in = null;
        try {
            in = url.openStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                Matcher m = nonCommentPattern.matcher(line);
                if (m.find()) {
                    providerNames.add(new ProviderName(m.group().trim(), url));
                }
            }
        } catch (IOException ioe) {
            throw new PersistenceException("IOException caught reading: " + url, ioe);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                }
            }
        }
    }

    /**
     * Clear all cached providers
     */
    public void clearCachedProviders() {
        this.providers.clear();
    }

    /**
     * A ProviderName captures each provider name found in a service file as
     * well as the URL for the service file it was found in. This
     * information is only used for diagnostic purposes.
     */
    private class ProviderName {

        /** Provider name **/
        private String name;

        /** URL for the service file where the provider name was found **/
        private URL source;

        public ProviderName(String name, URL sourceURL) {
            this.name = name;
            this.source = sourceURL;
        }

        public String getName() {
            return name;
        }

        public URL getSource() {
            return source;
        }

        public String toString() {
            return getName() + " - " + getSource();
        }
    }

    /**
     * The common interface to get a CacheKey implemented by
     * LoaderReference and PersistenceProviderReference.
     */
    private interface CacheKeyReference {
        public CacheKey getCacheKey();
    }

    /**
     * Key used for cached persistence providers. The key checks
     * the class loader to determine if the persistence providers
     * is a match to the requested one. The loader may be null.
     */
    private class CacheKey implements Cloneable {

        /* Weak Reference to ClassLoader */
        private LoaderReference loaderRef;

        /* Cached Hashcode */
        private int hashCodeCache;

        CacheKey(ClassLoader loader) {
            if (loader == null) {
                this.loaderRef = null;
            } else {
                loaderRef = new LoaderReference(loader, referenceQueue, this);
            }
            calculateHashCode();
        }

        ClassLoader getLoader() {
            return (loaderRef != null) ? loaderRef.get() : null;
        }

        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            try {
                final CacheKey otherEntry = (CacheKey) other;
                // quick check to see if they are not equal
                if (hashCodeCache != otherEntry.hashCodeCache) {
                    return false;
                }
                // are refs (both non-null) or (both null)?
                if (loaderRef == null) {
                    return otherEntry.loaderRef == null;
                }
                ClassLoader loader = loaderRef.get();
                return (otherEntry.loaderRef != null)
                        // with a null reference we can no longer find
                        // out which class loader was referenced; so
                        // treat it as unequal
                        && (loader != null) && (loader == otherEntry.loaderRef.get());
            } catch (NullPointerException e) {
            } catch (ClassCastException e) {
            }

            return false;
        }

        public int hashCode() {
            return hashCodeCache;
        }

        private void calculateHashCode() {
            ClassLoader loader = getLoader();
            if (loader != null) {
                hashCodeCache = loader.hashCode();
            }
        }

        public Object clone() {
            try {
                CacheKey clone = (CacheKey) super.clone();
                if (loaderRef != null) {
                    clone.loaderRef = new LoaderReference(loaderRef.get(), referenceQueue, clone);
                }
                return clone;
            } catch (CloneNotSupportedException e) {
                // this should never happen
                throw new InternalError();
            }
        }

        public String toString() {
            return "CacheKey[" + getLoader() + ")]";
        }
    }

    /**
     * References to class loaders are weak references, so that they can be
     * garbage collected when nobody else is using them. The DefaultPersistenceProviderResolver
     * class has no reason to keep class loaders alive.
     */
    private class LoaderReference extends WeakReference<ClassLoader>
            implements CacheKeyReference {
        private CacheKey cacheKey;

        LoaderReference(ClassLoader referent, ReferenceQueue q, CacheKey key) {
            super(referent, q);
            cacheKey = key;
        }

        public CacheKey getCacheKey() {
            return cacheKey;
        }
    }

    /**
     * References to persistence provider are soft references so that they can be garbage
     * collected when they have no hard references.
     */
    private class PersistenceProviderReference extends SoftReference<List<PersistenceProvider>>
            implements CacheKeyReference {
        private CacheKey cacheKey;

        PersistenceProviderReference(List<PersistenceProvider> referent, ReferenceQueue q, CacheKey key) {
            super(referent, q);
            cacheKey = key;
        }

        public CacheKey getCacheKey() {
            return cacheKey;
        }
    }
}
