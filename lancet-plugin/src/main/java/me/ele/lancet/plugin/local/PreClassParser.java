package me.ele.lancet.plugin.local;

import com.android.build.api.transform.JarInput;
import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.Status;
import me.ele.lancet.plugin.Util;
import me.ele.lancet.plugin.local.content.JarContentProvider;
import me.ele.lancet.plugin.local.content.ContextThreadPoolProcessor;
import me.ele.lancet.plugin.local.content.QualifiedContentProvider;
import me.ele.lancet.plugin.local.preprocess.AsmClassProcessorImpl;
import me.ele.lancet.plugin.local.preprocess.ParseFailureException;
import me.ele.lancet.plugin.local.preprocess.PreClassProcessor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Created by gengwanpeng on 17/4/26.
 */
public class PreClassParser {

    private LocalCache cache;
    private MetaGraphGeneratorImpl graph;
    private PreClassProcessor classProcessor = new AsmClassProcessorImpl();


    private ContextThreadPoolProcessor contextProcessor;


    private volatile boolean partial = true;

    public PreClassParser(LocalCache cache) {
        this.cache = cache;
        this.graph = new MetaGraphGeneratorImpl();
    }

    public boolean execute(TransformContext context) throws IOException, InterruptedException {
        contextProcessor = new ContextThreadPoolProcessor(context);
        if (context.isIncremental() && cache.canBeIncremental(context) && tryPartialParse(context)) {
            onComplete(null, context);
            return true;
        }

        partial = false;
        cache.clear();
        context.clear();

        onComplete(fullyParse(context), context);
        return false;
    }

    private boolean tryPartialParse(TransformContext context) throws IOException, InterruptedException {
        cache.accept(graph);
        contextProcessor.process(true, new SingleProcessor());
        return partial;
    }

    private void onComplete(SingleProcessor singleProcessor, TransformContext context) {
        if (partial) {
            cache.savePartially(graph.toLocalNodes());
        } else {
            cache.saveFully(graph.toLocalNodes(), singleProcessor.classes, singleProcessor.classesInDirs, singleProcessor.jarWithHookClasses);
        }

        context.setNodesMap(graph.generate());
        context.setClasses(cache.classes());
    }

    private void removeClasses(Collection<JarInput> removedJars) throws IOException {
        QualifiedContentProvider contentProvider = new JarContentProvider();
        SingleProcessor processor = new SingleProcessor();
        for (JarInput jarInput : removedJars) {
            contentProvider.forEach(jarInput, processor);
        }
    }


    private SingleProcessor fullyParse(TransformContext context) throws IOException, InterruptedException {
        SingleProcessor singleProcessor = new SingleProcessor();

        contextProcessor.process(false, singleProcessor);
        return singleProcessor;
    }

    class SingleProcessor implements QualifiedContentProvider.SingleClassProcessor {

        List<String> classes = new ArrayList<>(4);
        List<String> classesInDirs = new ArrayList<>(4);
        List<String> jarWithHookClasses = new ArrayList<>(4);

        @Override
        public boolean onStart(QualifiedContent content) {
            return true;
        }

        @Override
        public void onProcess(QualifiedContent content, Status status, String relativePath, byte[] bytes) {
            if(relativePath.endsWith(".class")) {
                PreClassProcessor.ProcessResult result = classProcessor.process(bytes);
                if (partial && result.isHookClass) {
                    partial = false;
                    throw new ParseFailureException();
                }
                if (result.isHookClass) {
                    synchronized (this) {
                        classes.add(result.className);
                        if (content instanceof JarInput) {
                            jarWithHookClasses.add(content.getFile().getAbsolutePath());
                        } else {
                            classesInDirs.add(Util.toSystemDependentFile(content.getFile(), relativePath).getAbsolutePath());
                        }
                    }
                }
                if (status != Status.REMOVED) {
                    graph.add(result.access, result.className, result.superName, result.interfaces);
                } else {
                    graph.remove(result.className);
                }
            }
        }

        @Override
        public void onComplete(QualifiedContent content) {
        }
    }
}