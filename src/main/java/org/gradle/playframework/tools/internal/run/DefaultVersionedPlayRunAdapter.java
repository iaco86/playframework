package org.gradle.playframework.tools.internal.run;

import org.gradle.internal.Cast;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.classpath.DefaultClassPath;
import org.gradle.playframework.tools.internal.reflection.DirectInstantiator;
import org.gradle.playframework.tools.internal.reflection.JavaReflectionUtil;
import org.gradle.playframework.tools.internal.scala.ScalaMethod;
import org.gradle.playframework.tools.internal.scala.ScalaReflectionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.lang.ref.SoftReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;

public abstract class DefaultVersionedPlayRunAdapter implements VersionedPlayRunAdapter, Serializable {

    private static final String PLAY_EXCEPTION_CLASSNAME = "play.api.PlayException";
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultVersionedPlayRunAdapter.class);

    private final AtomicReference<ClassLoader> currentClassloader = new AtomicReference<ClassLoader>();
    private final Queue<SoftReference<Closeable>> loadersToClose = new ConcurrentLinkedQueue<SoftReference<Closeable>>();

    protected abstract Class<?> getBuildLinkClass(ClassLoader classLoader) throws ClassNotFoundException;

    protected abstract Class<?> getDocHandlerFactoryClass(ClassLoader classLoader) throws ClassNotFoundException;

    protected abstract Class<?> getBuildDocHandlerClass(ClassLoader docsClassLoader) throws ClassNotFoundException;

    protected abstract ClassLoader createAssetsClassLoader(File assetsJar, Iterable<File> assetsDirs, ClassLoader classLoader);

    @Override
    public Object getBuildLink(final ClassLoader classLoader, final Reloader reloader, final File projectPath, final File applicationJar, final Iterable<File> changingClasspath, final File assetsJar, final Iterable<File> assetsDirs) throws ClassNotFoundException {
        final ClassLoader assetsClassLoader = createAssetsClassLoader(assetsJar, assetsDirs, classLoader);
        final Class<? extends Throwable> playExceptionClass = Cast.uncheckedCast(classLoader.loadClass(PLAY_EXCEPTION_CLASSNAME));

        return Proxy.newProxyInstance(classLoader, new Class<?>[]{getBuildLinkClass(classLoader)}, new InvocationHandler() {
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if (method.getName().equals("projectPath")) {
                    return projectPath;
                } else if (method.getName().equals("reload")) {
                    Reloader.Result result = reloader.requireUpToDate();

                    // We can't close replaced loaders immediately, because their classes may be used during shutdown,
                    // after the return of the reload() call that caused the loader to be swapped out.
                    // We have no way of knowing when the loader is actually done with, so we use the request after the request
                    // that triggered the reload as the trigger point to close the replaced loader.
                    closeOldLoaders();
                    if (result.changed) {
                        ClassPath classpath = DefaultClassPath.of(applicationJar).plus(DefaultClassPath.of(changingClasspath));
                        URLClassLoader currentClassLoader = new URLClassLoader(classpath.getAsURLArray(), assetsClassLoader);
                        storeClassLoader(currentClassLoader);
                        return currentClassLoader;
                    } else {
                        Throwable failure = result.failure;
                        if (failure == null) {
                            return null;
                        } else {
                            try {
                                return DirectInstantiator.instantiate(playExceptionClass, "Gradle Build Failure", failure.getMessage(), failure);
                            } catch (Exception e) {
                                LOGGER.warn("Could not translate " + failure + " to " + PLAY_EXCEPTION_CLASSNAME, e);
                                return failure;
                            }
                        }
                    }
                } else if (method.getName().equals("settings")) {
                    return new HashMap<String, String>();
                }
                //TODO: all methods
                return null;
            }
        });
    }

    private void storeClassLoader(ClassLoader classLoader) {
        final ClassLoader previous = currentClassloader.getAndSet(classLoader);
        if (previous != null && previous instanceof Closeable) {
            loadersToClose.add(new SoftReference<Closeable>(Cast.cast(Closeable.class, previous)));
        }
    }

    private void closeOldLoaders() throws IOException {
        SoftReference<Closeable> ref = loadersToClose.poll();
        while (ref != null) {
            Closeable closeable = ref.get();
            if (closeable != null) {
                closeable.close();
            }
            ref.clear();
            ref = loadersToClose.poll();
        }
    }

    @Override
    public Object getBuildDocHandler(ClassLoader docsClassLoader, Iterable<File> classpath) throws NoSuchMethodException, ClassNotFoundException, IOException, IllegalAccessException {
        Class<?> docHandlerFactoryClass = getDocHandlerFactoryClass(docsClassLoader);
        Method docHandlerFactoryMethod = docHandlerFactoryClass.getMethod("fromJar", JarFile.class, String.class);
        JarFile documentationJar = findDocumentationJar(classpath);
        try {
            return docHandlerFactoryMethod.invoke(null, documentationJar, "play/docs/content");
        } catch (InvocationTargetException e) {
            throw UncheckedException.unwrapAndRethrow(e);
        }
    }

    private JarFile findDocumentationJar(Iterable<File> classpath) throws IOException {
        File docJarFile = null;
        for (File file : classpath) {
            if (file.getName().startsWith("play-docs")) {
                docJarFile = file;
                break;
            }
        }
        return new JarFile(docJarFile);
    }

    @Override
    public InetSocketAddress runDevHttpServer(ClassLoader classLoader, ClassLoader docsClassLoader, Object buildLink, Object buildDocHandler, int httpPort) throws ClassNotFoundException {
        ScalaMethod runMethod = ScalaReflectionUtil.scalaMethod(classLoader, "play.core.server.NettyServer", "mainDevHttpMode", getBuildLinkClass(classLoader), getBuildDocHandlerClass(docsClassLoader), int.class);
        Object reloadableServer = runMethod.invoke(buildLink, buildDocHandler, httpPort);
        return JavaReflectionUtil.method(reloadableServer, InetSocketAddress.class, "mainAddress").invoke(reloadableServer);
    }

}