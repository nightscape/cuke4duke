package cuke4duke.mojo;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.tools.ant.BuildEvent;
import org.apache.tools.ant.BuildListener;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.taskdefs.Java;
import org.apache.tools.ant.types.Commandline;
import org.apache.tools.ant.types.Environment;
import org.apache.tools.ant.types.Path;
import org.codehaus.plexus.util.StringUtils;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Base for all JRuby mojos.
 * 
 * @requiresDependencyResolution test
 */
public abstract class AbstractJRubyMojo extends AbstractMojo {

    /**
     * @parameter
     */
    protected boolean shouldFork = true;

    /**
     * @parameter expression="${project}"
     */
    protected MavenProject mavenProject;

    /**
     * @parameter expression="${project.basedir}"
     * @required
     */
    protected File launchDirectory;

    /**
     * The project compile classpath.
     * 
     * @parameter default-value="${project.compileClasspathElements}"
     * @required
     * @readonly
     */
    protected List<String> compileClasspathElements;

    /**
     * The plugin dependencies.
     * 
     * @parameter expression="${plugin.artifacts}"
     * @required
     * @readonly
     */
    protected List<Artifact> pluginArtifacts;

    /**
     * The project test classpath
     * 
     * @parameter expression="${project.testClasspathElements}"
     * @required
     * @readonly
     */
    protected List<String> testClasspathElements;

    /**
     * @parameter expression="${localRepository}"
     * @required
     * @readonly
     */
    protected ArtifactRepository localRepository;
    @SuppressWarnings("unchecked")
	public static void setEnvironmentVariable(String key, String value) throws Exception {
        Class[] classes = Collections.class.getDeclaredClasses();
        Map<String, String> env = System.getenv();
        for(Class cl : classes) {
            if("java.util.Collections$UnmodifiableMap".equals(cl.getName())) {
                Field field = cl.getDeclaredField("m");
                field.setAccessible(true);
                Object obj = field.get(env);
                Map<String, String> map = (Map<String, String>) obj;
                map.put(key,value);
            }
        }
    }
    protected Java jruby(List<String> args) throws MojoExecutionException {
        launchDirectory.mkdirs();
        Project project;
        try {
            project = getProject();
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("error resolving dependencies", e);
        }

        Java java = new Java();
        java.setProject(project);
        java.setClassname("org.jruby.Main");
        java.setFailonerror(true);

        Commandline.Argument arg;
        Path p = new Path(java.getProject());
        p.add((Path) project.getReference("maven.plugin.classpath"));
        p.add((Path) project.getReference("maven.compile.classpath"));
        p.add((Path) project.getReference("maven.test.classpath"));

        if (shouldFork) {
            java.setFork(true);
            java.setDir(launchDirectory);

            for (String jvmArg : getJvmArgs()) {
                arg = java.createJvmarg();
                arg.setValue(jvmArg);
            }

            Environment.Variable classpath = new Environment.Variable();


            classpath.setKey("JRUBY_PARENT_CLASSPATH");
            classpath.setValue(p.toString());

            java.addEnv(classpath);
        } else {
          for (String jvmArg : getJvmArgs()) {
            String[] keyAndValue = jvmArg.split("=");
            System.setProperty(keyAndValue[0],keyAndValue[1]);
          }
          try {
			setEnvironmentVariable("JRUBY_PARENT_CLASSPATH", p.toString());
			setEnvironmentVariable("MOE", "was here");
		} catch (Exception e) {
			e.printStackTrace();
		}
          
        }
        
        System.out.println("Environment is: "+System.getenv());
        System.out.println("Properties are: "+System.getProperties());

        Environment.Variable gemPathVar = new Environment.Variable();
        gemPathVar.setKey("GEM_PATH");
        gemPathVar.setValue(gemHome().getAbsolutePath());
        java.addEnv(gemPathVar);


        getLog().debug("java classpath: " + p.toString());

        for (String s : args) {
            arg = java.createArg();
            arg.setValue(s);
        }

        return java;
    }

    protected abstract List<String> getJvmArgs();

    /**
     * Installs a gem. Sources used:
     * <ul>
     * <li>http://gems.rubyforge.org</li>
     * <li>http://gemcutter.org/</li>
     * <li>http://gems.github.com</li>
     * </ul>
     * 
     * @param gemArgs
     *            name and optional arguments.
     *            Example:
     *            <ul>
     *            <li>awesome</li>
     *            <li>awesome --version 9.8</li>
     *            <li>awesome --version 9.8 --source http://some.gem.server</li>
     *            </ul>
     * @throws org.apache.maven.plugin.MojoExecutionException
     *             if gem installation fails.
     */
    protected void installGem(String gemArgs) throws MojoExecutionException {
        List<String> args = new ArrayList<String>();
        args.add("-S");
        args.add("gem");
        args.add("install");
        args.add("--no-ri");
        args.add("--no-rdoc");
        args.add("--install-dir");
        args.add(gemHome().getAbsolutePath());
        args.addAll(Arrays.asList(gemArgs.split("\\s+")));

        Java jruby = jruby(args);
        // We have to override HOME to make RubyGems install gems
        // where we want it. Setting GEM_HOME and using --install-dir
        // is not enough.
        Environment.Variable homeVar = new Environment.Variable();
        homeVar.setKey("HOME");
        homeVar.setValue(dotGemParent().getAbsolutePath());
        jruby.addEnv(homeVar);
        dotGemParent().mkdirs();
        jruby.execute();
    }

    protected File dotGemParent() {
        return new File(localRepository.getBasedir());
    }

    protected File gemHome() {
        return new File(dotGemParent(), ".gem");
    }

    protected File binDir() {
        return new File(gemHome(), "bin");
    }

    protected Project getProject() throws DependencyResolutionRequiredException {
        Project project = new Project();
        project.setBaseDir(mavenProject.getBasedir());
        project.addBuildListener(new LogAdapter());
        addReference(project, "maven.compile.classpath",
                compileClasspathElements);
        addReference(project, "maven.plugin.classpath", pluginArtifacts);
        addReference(project, "maven.test.classpath", testClasspathElements);
        return project;
    }

    protected void addReference(Project project, String reference,
            List<?> artifacts) throws DependencyResolutionRequiredException {
        List<String> list = new ArrayList<String>(artifacts.size());

        for (Object elem : artifacts) {
            String path;
            if (elem instanceof Artifact) {
                Artifact a = (Artifact) elem;
                File file = a.getFile();
                if (file == null) {
                    throw new DependencyResolutionRequiredException(a);
                }
                path = file.getPath();
            } else {
                path = elem.toString();
            }
            list.add(path);
        }

        Path p = new Path(project);
        p.setPath(StringUtils.join(list.iterator(), File.pathSeparator));
        project.addReference(reference, p);
    }

    public static <T> List<T> listify(T... objects) {
        List<T> res = new ArrayList<T>();
        res.addAll(Arrays.asList(objects));
        return res;
    }

    protected String cmd(Java jruby) {
        return join(jruby.getCommandLine().getCommandline());
    }

    protected String join(String[] strings) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < strings.length; i++) {
            if (i != 0)
                sb.append(' ');
            sb.append(strings[i]);
        }
        return sb.toString();
    }

    public class LogAdapter implements BuildListener {
        public void buildStarted(BuildEvent event) {
            log(event);
        }

        public void buildFinished(BuildEvent event) {
            log(event);
        }

        public void targetStarted(BuildEvent event) {
            log(event);
        }

        public void targetFinished(BuildEvent event) {
            log(event);
        }

        public void taskStarted(BuildEvent event) {
            log(event);
        }

        public void taskFinished(BuildEvent event) {
            log(event);
        }

        public void messageLogged(BuildEvent event) {
            log(event);
        }

        private void log(BuildEvent event) {
            int priority = event.getPriority();
            Log log = getLog();
            String message = event.getMessage();
            switch (priority) {
            case Project.MSG_ERR:
                log.error(message);
                break;

            case Project.MSG_WARN:
                log.warn(message);
                break;

            case Project.MSG_INFO:
                log.info(message);
                break;

            case Project.MSG_VERBOSE:
                log.debug(message);
                break;

            case Project.MSG_DEBUG:
                log.debug(message);
                break;

            default:
                log.info(message);
                break;
            }
        }
    }
}
