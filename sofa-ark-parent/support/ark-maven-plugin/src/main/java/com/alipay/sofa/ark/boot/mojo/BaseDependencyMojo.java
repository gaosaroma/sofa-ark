/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.ark.boot.mojo;

import com.alipay.sofa.ark.tools.ArtifactItem;
import org.apache.commons.io.FileUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.InvalidPluginDescriptorException;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.PluginConfigurationException;
import org.apache.maven.plugin.PluginDescriptorParsingException;
import org.apache.maven.plugin.PluginManagerException;
import org.apache.maven.plugin.PluginNotFoundException;
import org.apache.maven.plugin.PluginResolutionException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.plugins.dependency.resolvers.ListMojo;
import org.apache.maven.plugins.dependency.tree.TreeMojo;
import org.apache.maven.plugins.dependency.utils.DependencyStatusSets;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.alipay.sofa.ark.boot.mojo.MavenUtils.buildPomModel;
import static com.alipay.sofa.ark.boot.mojo.MavenUtils.getRootProject;

/**
 * @author lianglipeng.llp@alibaba-inc.com
 * @version $Id: BaseDependencyMojo.java, v 0.1 2024年07月11日 16:21 立蓬 Exp $
 */
@Mojo(name = "baseDependency", defaultPhase = LifecyclePhase.PACKAGE, requiresProject = true, threadSafe = true, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class BaseDependencyMojo extends TreeMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject           mavenProject;

    @Component
    private MavenSession           mavenSession;

    @Parameter(defaultValue = "${project.basedir}", required = true)
    private File                   baseDir;

    @Parameter(defaultValue = "${project.version}")
    private String                 version;

    @Parameter(defaultValue = "artifact")
    private String                 artifactId;

    @Parameter(defaultValue = "${project.groupId}", required = true)
    private String                 groupId;

    /**
     * mvn command user properties
     */
    private ProjectBuildingRequest projectBuildingRequest;

    @Parameter(defaultValue = "true")
    private String                 cleanAfterPackage;

    private static class DependencyListMojo extends ListMojo {
        MavenProject mavenProject;

        DependencyListMojo(MavenProject project) {
            mavenProject = project;
        }

        public DependencyStatusSets getDependencySets() throws MojoExecutionException {
            return super.getDependencySets(false);
        }

        @Override
        public MavenProject getProject() {
            return this.mavenProject;
        }
    }
    @Override
    public void execute() throws MojoExecutionException {
        projectBuildingRequest = this.mavenProject.getProjectBuildingRequest();
        File facadeRootDir = null;
        try {
            //0. 创建一个空maven工程（跟当前工程没关系），准备好各种文件、目录。
            facadeRootDir = new File(baseDir, artifactId);
            if (facadeRootDir.exists()) {
                FileUtils.deleteQuietly(facadeRootDir);
            }
            if (!facadeRootDir.exists()) {
                facadeRootDir.mkdirs();
            }

            File facadePom = new File(facadeRootDir, "pom.xml");
            if (!facadePom.exists()) {
                facadePom.createNewFile();
            }
            getLog().info("create base facade directory success." + facadeRootDir.getAbsolutePath());

            // 2. 解析所有依赖，写入pom
            // 把所有依赖找到，平铺写到pom (同时排掉指定的依赖, 以及基座的子module)
            BufferedWriter pomWriter = new BufferedWriter(new FileWriter(facadePom, true));
            pomWriter.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            pomWriter.write("<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
                    + "xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
            pomWriter.write("    <modelVersion>4.0.0</modelVersion>\n");

            // 先随便指定一下 parent
            pomWriter.write("<parent>\n");
            pomWriter.write("<groupId>com.alipay.sofa</groupId>\n");
            pomWriter.write("<artifactId>sofaboot-alipay-dependencies</artifactId>\n");
            pomWriter.write("<version>3.26.0</version>\n");
            pomWriter.write("</parent>\n");

            pomWriter.write("    <groupId>" + groupId + "</groupId>\n");
            pomWriter.write("    <artifactId>" + artifactId + "</artifactId>\n");
            pomWriter.write("    <version>" + version + "</version>\n");
            pomWriter.write("    <packaging>pom</packaging>\n");

            // 配置 license
            pomWriter.write("<licenses>\n");
            pomWriter.write("<license>\n");
            pomWriter.write("<name>The Apache License, Version 2.0</name>\n");
            pomWriter.write("<url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>\n");
            pomWriter.write("</license>\n");
            pomWriter.write("</licenses>\n");
            pomWriter.flush();

            // 解析基座所有子module，用于exclude
            Set<String> baseModuleArtifactIds = getBaseModuleArtifactIds();
            Set<ArtifactItem> artifactItems = getArtifactList();
            if (artifactItems != null && !artifactItems.isEmpty()) {
                pomWriter.write("<dependencyManagement>\n");
                pomWriter.write("<dependencies>\n");
                for (ArtifactItem i : artifactItems) {
                    if (baseModuleArtifactIds.contains(i.getArtifactId())) {
                        continue;
                    }
                    pomWriter.write("<dependency>\n");
                    pomWriter.write("<groupId>" + i.getGroupId() + "</groupId>\n");
                    pomWriter.write("<artifactId>" + i.getArtifactId() + "</artifactId>\n");
                    pomWriter.write("<version>" + i.getVersion() + "</version>\n");
                    pomWriter.write("</dependency>\n");
                    pomWriter.flush();
                }
                pomWriter.write("</dependencies>\n");
                pomWriter.write("</dependencyManagement>\n");
            }

            // 打pom包
            pomWriter.write("<build>\n");
            pomWriter.write("<plugins>\n");

            // maven-source-plugin
            pomWriter.write("<plugin>\n");
            pomWriter.write("<groupId>org.apache.maven.plugins</groupId>\n");
            pomWriter.write("<artifactId>maven-source-plugin</artifactId>\n");
            pomWriter.write("<version>2.0.2</version>\n");
            pomWriter.write("<executions>\n");
            pomWriter.write("<execution>\n");
            pomWriter.write("<id>attach-sources</id>\n");
            pomWriter.write("<goals>\n");
            pomWriter.write("<goal>jar</goal>\n");
            pomWriter.write("</goals>\n");
            pomWriter.write("</execution>\n");
            pomWriter.write("</executions>\n");
            pomWriter.write("</plugin>\n");


            // maven-compiler-plugin
            pomWriter.write("<plugin>\n");
            pomWriter.write("<groupId>org.apache.maven.plugins</groupId>\n");
            pomWriter.write("<artifactId>maven-compiler-plugin</artifactId>\n");
            pomWriter.write("<version>3.8.1</version>\n");
            pomWriter.write("<configuration>\n");
            pomWriter.write("<source>1.8</source>\n");
            pomWriter.write("<target>1.8</target>\n");
            pomWriter.write("</configuration>\n");
            pomWriter.write("</plugin>\n");

            // flatten-maven-plugin
            pomWriter.write("<plugin>\n");
            pomWriter.write("<groupId>org.codehaus.mojo</groupId>\n");
            pomWriter.write("<artifactId>flatten-maven-plugin</artifactId>\n");
            pomWriter.write("<executions>\n");
            pomWriter.write("<execution>\n");
            pomWriter.write("<id>flatten</id>\n");
            pomWriter.write("<phase>process-resources</phase>\n");
            pomWriter.write("<goals>\n");
            pomWriter.write("<goal>flatten</goal>\n");
            pomWriter.write("</goals>\n");
            pomWriter.write("</execution>\n");
            pomWriter.write("<execution>\n");
            pomWriter.write("<id>flatten.clean</id>\n");
            pomWriter.write("<phase>clean</phase>\n");
            pomWriter.write("<goals>\n");
            pomWriter.write("<goal>clean</goal>\n");
            pomWriter.write("</goals>\n");
            pomWriter.write("</execution>\n");
            pomWriter.write("</executions>\n");
            pomWriter.write("<inherited>false</inherited>\n");
            pomWriter.write("<configuration>\n");
            pomWriter.write("<updatePomFile>true</updatePomFile>\n");
            pomWriter.write("<flattenMode>resolveCiFriendliesOnly</flattenMode>\n");
            pomWriter.write("</configuration>\n");
            pomWriter.write("</plugin>\n");
            pomWriter.write("</plugins>\n");
            pomWriter.write("</build>\n");
            pomWriter.write("</project>");
            pomWriter.close();
            getLog().info("analyze all dependencies and write facade pom.xml success.");

            //3. 打包
            InvocationRequest request = new DefaultInvocationRequest();
            request.setPomFile(facadePom);
            List<String> goals = Stream.of("install").collect(Collectors.toList());
            Properties userProperties = projectBuildingRequest.getUserProperties();
            if (userProperties != null) {
                userProperties.forEach((key, value) -> goals.add(String.format("-D%s=%s", key, value)));
            }
            request.setGoals(goals);
            request.setBatchMode(mavenSession.getSettings().getInteractiveMode());
            request.setProfiles(mavenSession.getSettings().getActiveProfiles());
            setSettingsLocation(request);
            Invoker invoker = new DefaultInvoker();
            InvocationResult result = invoker.execute(request);
            if (result.getExitCode() != 0) {
                throw new MojoExecutionException("execute mvn install failed for serverless facade",
                        result.getExecutionException());
            }
            getLog().info("package base facade success.");
            ////4.移动构建出来的jar、source jar、pom到outputs目录
            //File outputsDir = new File(baseDir, "outputs");
            //if (!outputsDir.exists()) {
            //    outputsDir.mkdirs();
            //}
            //File facadeTargetDir = new File(facadeRootDir, "target");
            //File[] targetFiles = facadeTargetDir.listFiles();
            //for (File f : targetFiles) {
            //    if (f.getName().endsWith(".jar")) {
            //        File newFile = new File(outputsDir, f.getName());
            //        Files.copy(f.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            //        getLog().info("copy " + f.getAbsolutePath() + " to " + newFile.getAbsolutePath() + " success.");
            //    }
            //}
            //File newPomFile = new File(outputsDir, "pom.xml");
            //Files.copy(facadePom.toPath(), newPomFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            //getLog().info("copy pom.xml to " + newPomFile.getAbsolutePath() + " success.");
        } catch (Exception e) {
            throw new MojoExecutionException("package serverless facade exception", e);
        } finally {
            //// 4. 清理
            //if ("true".equals(cleanAfterPackage) && facadeRootDir != null) {
            //    FileUtils.deleteQuietly(facadeRootDir);
            //}
        }
    }

    private Set<String> getBaseModuleArtifactIds() {
        List<String> baseModules = getRootProject(this.mavenProject).getModel().getModules();
        File basedir = getRootProject(this.mavenProject).getBasedir();
        Set<String> baseModuleArtifactIds = new HashSet<>();
        for (String module : baseModules) {
            String modulePath = new File(basedir, module).getAbsolutePath();
            Model modulePom = buildPomModel(modulePath + File.separator + "pom.xml");
            String artifactId = modulePom.getArtifactId();
            getLog().info("find maven module of base: " + artifactId);
            baseModuleArtifactIds.add(artifactId);
        }
        return baseModuleArtifactIds;
    }

    private Set<ArtifactItem> getArtifactList() throws MojoExecutionException {
        getLog().info("root project path: " + baseDir.getAbsolutePath());

        // dependency:tree
        String outputPath = baseDir.getAbsolutePath() + "/deps.log." + System.currentTimeMillis();
        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(new File(baseDir.getAbsolutePath() + "/pom.xml"));

        List<String> goals = Stream.of("dependency:tree", "-DoutputFile=" + outputPath).collect(Collectors.toList());

        Properties userProperties = projectBuildingRequest.getUserProperties();
        if (userProperties != null) {
            userProperties.forEach((key, value) -> goals.add(String.format("-D%s=%s", key, value)));
        }

        getLog().info(
                "execute 'mvn dependency:tree' with command 'mvn " + String.join(" ", goals) + "'");
        request.setGoals(goals);
        request.setBatchMode(mavenSession.getSettings().getInteractiveMode());
        request.setProfiles(mavenSession.getSettings().getActiveProfiles());
        setSettingsLocation(request);
        Invoker invoker = new DefaultInvoker();
        try {
            InvocationResult result = invoker.execute(request);
            if (result.getExitCode() != 0) {
                throw new MojoExecutionException("execute dependency:tree failed",
                        result.getExecutionException());
            }

            String depTreeStr = FileUtils.readFileToString(FileUtils.getFile(outputPath),
                    Charset.defaultCharset());
            return MavenUtils.convert(depTreeStr);
        } catch (MavenInvocationException | IOException e) {
            throw new MojoExecutionException("execute dependency:tree failed", e);
        } finally {
            File outputFile = new File(outputPath);
            if (outputFile.exists()) {
                outputFile.delete();
            }
        }
    }

    private void setSettingsLocation(InvocationRequest request) {
        File userSettingsFile = mavenSession.getRequest().getUserSettingsFile();
        if (userSettingsFile != null && userSettingsFile.exists()) {
            request.setUserSettingsFile(userSettingsFile);
        }
        File globalSettingsFile = mavenSession.getRequest().getGlobalSettingsFile();
        if (globalSettingsFile != null && globalSettingsFile.exists()) {
            request.setGlobalSettingsFile(globalSettingsFile);
        }
    }
}