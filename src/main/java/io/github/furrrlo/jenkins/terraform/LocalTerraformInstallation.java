package io.github.furrrlo.jenkins.terraform;

import hudson.util.LogTaskListener;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.terraform.Configuration;
import org.jenkinsci.plugins.terraform.Messages;
import org.jenkinsci.plugins.terraform.TerraformInstallation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class LocalTerraformInstallation extends TerraformInstallation {

    private static final String WORK_DIR_NAME = "terraform-cloud-plugin";
    private static final String STATE_FILE_NAME = "terraform-cloud-plugin.tfstate";
    private static final String VARIABLES_FILE_NAME = "terraform-cloud-plugin.tfvars";

    public LocalTerraformInstallation(TerraformInstallation actualInstallation) throws IOException, InterruptedException {
        this(actualInstallation.forNode(Jenkins.get(), new LogTaskListener(
                        java.util.logging.Logger.getLogger(LocalTerraformInstallation.class.getName()),
                        java.util.logging.Level.INFO)),
                null);
    }

    private LocalTerraformInstallation(TerraformInstallation actualInstallation,
                                       @SuppressWarnings("unused") Void ignore) {
        super(actualInstallation.getName(), actualInstallation.getHome(), actualInstallation.getProperties());
    }

    public File getLocalExecutable() throws FileNotFoundException {
        final String homeStr = getHome();
        if(homeStr == null)
            throw new FileNotFoundException(Messages.HomeDirectoryNotFound(null));

        final File homeDirectory = new File(homeStr);
        if (!(homeDirectory.exists() && homeDirectory.isDirectory()))
            throw new FileNotFoundException(Messages.HomeDirectoryNotFound(homeDirectory));

        File executable = new File(homeDirectory, getExecutableFilename());
        if (!executable.exists())
            throw new FileNotFoundException(Messages.ExecutableNotFound(homeDirectory));

        return executable;
    }

    public WorkDir setupWorkDir(File rootDirectory,
                                String workDirectoryName,
                                Configuration config,
                                Map<String, String> variables) throws IOException {
        final File workingDirectory = new File(rootDirectory, WORK_DIR_NAME + File.separator + workDirectoryName);
        Files.createDirectories(workingDirectory.toPath());

        final File stateFile = new File(workingDirectory, STATE_FILE_NAME);
        final File variablesFile = new File(workingDirectory, VARIABLES_FILE_NAME);

        switch (config.getMode()) {
            case INLINE:
                final File configFile;
                try {
                    configFile = File.createTempFile("terraform", ".tf", workingDirectory);
                    Files.write(configFile.toPath(), config.getInlineConfig().getBytes(StandardCharsets.UTF_8));
                } catch (IOException ex) {
                    throw new IOException(Messages.ConfigurationNotCreated());
                }

                if (!configFile.exists())
                    throw new FileNotFoundException(Messages.ConfigurationNotCreated());

                return new WorkDir(this, variables, workingDirectory, stateFile, variablesFile);
            case FILE:
                if (config.getFileConfig() == null || config.getFileConfig().equals(""))
                    return new WorkDir(this, variables, workingDirectory, stateFile, variablesFile);

                final Path configToCopy = new File(rootDirectory, config.getFileConfig()).toPath();
                if (!Files.isDirectory(configToCopy))
                    throw new FileNotFoundException(Messages.ConfigurationPathNotFound(configToCopy));

                Files.walkFileTree(configToCopy, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        Files.createDirectory(workingDirectory.toPath().resolve(configToCopy.relativize(dir)));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.copy(file, workingDirectory.toPath().resolve(configToCopy.relativize(file)));
                        return FileVisitResult.CONTINUE;
                    }
                });

                return new WorkDir(this, variables, workingDirectory, stateFile, variablesFile);
            default:
                throw new RuntimeException(Messages.InvalidConfigMode());
        }
    }

    @SuppressWarnings("unused")
    public static class WorkDir implements Closeable {

        private static final Logger LOGGER = LoggerFactory.getLogger(WorkDir.class);
        private static final Pattern ANSI_ESCAPE_REGEX = Pattern.compile("\u001B\\[[\\d;]*m");
        private static final ExecutorService STREAM_GOBBLER_EXECUTOR = Executors.newCachedThreadPool(new ThreadFactory() {

            private final AtomicInteger i = new AtomicInteger();

            @Override
            public Thread newThread(@Nonnull Runnable r) {
                Thread t = Executors.defaultThreadFactory().newThread(r);
                t.setName("terraform-stream-gobbler-" + i.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });

        private final LocalTerraformInstallation installation;
        private final Map<String, String> variables;

        private final File pwd;
        private final File stateFile;
        private final File variablesFile;

        public WorkDir(LocalTerraformInstallation installation,
                       Map<String, String> variables,
                       File pwd,
                       File stateFile,
                       File variablesFile) {
            this.installation = installation;
            this.variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables));
            this.pwd = pwd;
            this.stateFile = stateFile;
            this.variablesFile = variablesFile;
        }

        @Override
        public void close() throws IOException {
            Files.walkFileTree(pwd.toPath(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        public Closeable writeFileVariable() throws IOException {
            Files.write(variablesFile.toPath(), variables.entrySet().stream()
                    .map(e -> e.getKey() + "= \"" + e.getValue().replace("\"", "\\\"") + '"')
                    .collect(Collectors.joining("\n"))
                    .getBytes(StandardCharsets.UTF_8));
            return () -> Files.deleteIfExists(variablesFile.toPath());
        }

        public <T> T runTerraformCmd(Function<ProcessBuilder, ProcessBuilder> decorator,
                                     ProcessWaitFn<T> waitFn) throws IOException {
            return runTerraformCmd(decorator, false, waitFn);
        }

        @SuppressWarnings("UnusedReturnValue")
        public <T> T runTerraformCmd(Function<ProcessBuilder, ProcessBuilder> decorator,
                                     boolean removeAnsiColors,
                                     ProcessWaitFn<T> waitFn) throws IOException {
            final ProcessBuilder pb = decorator.apply(new ProcessBuilder()
                    .command(installation.getLocalExecutable().getAbsolutePath())
                    .directory(pwd)
                    .redirectErrorStream(true));
            LOGGER.info("Launching Terraform command: {}", pb.command());
            final Process process = pb.start();

            try(BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
                final List<String> output = Collections.synchronizedList(new ArrayList<>());
                final CompletableFuture<?> streamGobbler = CompletableFuture.runAsync(() -> {
                    try {
                        String line;
                        while(!Thread.currentThread().isInterrupted() && (line = reader.readLine()) != null) {
                            if(removeAnsiColors)
                                line = ANSI_ESCAPE_REGEX.matcher(line).replaceAll("");

                            LOGGER.info("[TERRAFORM] {}", line);
                            output.add(line);
                        }
                    } catch (IOException e) {
                        // Ignored
                    }
                }, STREAM_GOBBLER_EXECUTOR);

                try {
                    return waitFn.wait(process, () -> {
                        synchronized (output) {
                            return new ArrayList<>(output);
                        }
                    });
                } catch (Throwable t) {
                    final String msg;
                    synchronized (output) {
                        msg = output.stream()
                                .map(line -> "\t\t" + line)
                                .collect(Collectors.joining("\n", "\"\"\n", "\n\"\""));
                    }
                    throw new IOException("Terraform command failed " + msg);
                } finally {
                    streamGobbler.cancel(true);
                }
            }
        }

        public LocalTerraformInstallation getInstallation() {
            return installation;
        }

        public Map<String, String> getVariables() {
            return variables;
        }

        public File getPwd() {
            return pwd;
        }

        public File getStateFile() {
            return stateFile;
        }

        public File getVariablesFile() {
            return variablesFile;
        }
    }

    public interface ProcessWaitFn<R> {

        R wait(Process t, Supplier<List<String>> outputSupplier) throws Exception;
    }
}
