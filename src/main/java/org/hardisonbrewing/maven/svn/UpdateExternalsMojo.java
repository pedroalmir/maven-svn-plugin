/**
 * Copyright (c) 2013 Martin M Reed
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.hardisonbrewing.maven.svn;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Scm;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.Commandline;
import org.hardisonbrewing.maven.core.DependencyService;
import org.hardisonbrewing.maven.core.FileUtils;
import org.hardisonbrewing.maven.core.JoJoMojoImpl;
import org.hardisonbrewing.maven.core.ProjectService;
import org.hardisonbrewing.maven.core.TargetDirectoryService;
import org.hardisonbrewing.maven.core.cli.CommandLineService;

/**
 * @goal update-externals
 * @phase update-externals
 */
public final class UpdateExternalsMojo extends JoJoMojoImpl {

    /**
     * @parameter
     */
    private External[] externals;

    private File checkoutDirectory;

    @Override
    public final void execute() throws MojoExecutionException, MojoFailureException {

        if ( externals == null ) {
            getLog().error( "Must specify <externals/> list!" );
            throw new IllegalStateException();
        }

        StringBuffer stringBuffer = new StringBuffer();
        stringBuffer.append( TargetDirectoryService.getTargetDirectoryPath() );
        stringBuffer.append( File.separator );
        stringBuffer.append( "checkout" );
        String checkoutDirPath = stringBuffer.toString();

        checkoutDirectory = new File( checkoutDirPath );
        if ( !checkoutDirectory.exists() ) {
            checkoutDirectory.mkdirs();
        }

        try {
            checkout();
            updateExternals();
            commit();
        }
        catch (Exception e) {
            throw new IllegalStateException( e );
        }
    }

    @Override
    protected Commandline buildCommandline( List<String> cmd ) {

        Commandline commandLine;

        try {
            commandLine = CommandLineService.build( cmd );
        }
        catch (CommandLineException e) {
            throw new IllegalStateException( e.getMessage() );
        }

        commandLine.setWorkingDirectory( checkoutDirectory );

        return commandLine;
    }

    private void checkout() throws Exception {

        Properties properties = PropertiesService.getExternalsProperties();
        String url = properties.getProperty( PropertiesService.SCM_URL );

        List<String> cmd = new LinkedList<String>();
        cmd.add( "svn" );
        cmd.add( "checkout" );
        cmd.add( "--depth" );
        cmd.add( "empty" );
        cmd.add( url );
        cmd.add( "." );
        execute( cmd );
    }

    private void updateExternals() throws Exception {

        List<Entry> properties = loadExternals();

        for (External external : externals) {
            updateExternal( properties, external );
        }

        writeExternals( properties );
    }

    private List<Entry> loadExternals() {

        List<String> cmd = new LinkedList<String>();
        cmd.add( "svn" );
        cmd.add( "propget" );
        cmd.add( "svn:externals" );

        List<Entry> properties = new LinkedList<Entry>();
        PropertyStreamConsumer streamConsumer = new PropertyStreamConsumer( properties );
        execute( cmd, streamConsumer, null );

        return properties;
    }

    private void updateExternal( List<Entry> properties, External external ) throws Exception {

        Dependency dependency = external.dependency;

        if ( dependency == null ) {
            getLog().error( "Must specify external dependency!" );
            throw new IllegalStateException();
        }

        StringBuffer dependencyStr = new StringBuffer();
        dependencyStr.append( dependency.getGroupId() );
        dependencyStr.append( ":" );
        dependencyStr.append( dependency.getArtifactId() );
        dependencyStr.append( ":" );
        dependencyStr.append( dependency.getVersion() );
        dependencyStr.append( ":" );
        dependencyStr.append( dependency.getType() );

        if ( external.path == null ) {
            getLog().error( "No path specified for external: " + dependencyStr );
            throw new IllegalStateException();
        }

        Artifact artifact = DependencyService.createResolvedArtifact( dependency );
        MavenProject project = ProjectService.getProject( artifact );
        Scm scm = project.getScm();

        if ( scm == null ) {
            getLog().error( "No SCM specified for " + dependencyStr );
            throw new IllegalStateException();
        }

        String url = scm.getUrl();

        if ( url == null || url.length() == 0 ) {
            getLog().error( "No SCM Url specified for " + dependencyStr );
            throw new IllegalStateException();
        }

        getLog().error( "Setting svn:externals for " + dependencyStr + " to '" + url + " " + external.path + "'" );

        Entry entry = findEntry( properties, external.path );

        if ( entry == null ) {
            entry = new Entry();
            properties.add( entry );
        }

        entry.first = url;
        entry.second = external.path;
    }

    private Entry findEntry( List<Entry> properties, String path ) {

        for (Entry entry : properties) {
            if ( path.equals( entry.first ) || path.equals( entry.second ) ) {
                return entry;
            }
        }

        return null;
    }

    private void writeExternals( List<Entry> properties ) throws Exception {

        String targetDirectoryPath = TargetDirectoryService.getTargetDirectoryPath();
        File file = new File( targetDirectoryPath, "svn.externals" );

        writeExternals( properties, file );

        List<String> cmd = new LinkedList<String>();
        cmd.add( "svn" );
        cmd.add( "propset" );
        cmd.add( "svn:externals" );
        cmd.add( "-F" );
        cmd.add( file.getPath() );
        cmd.add( "." );
        execute( cmd );
    }

    private void writeExternals( List<Entry> properties, File file ) throws Exception {

        FileUtils.ensureParentExists( file );

        OutputStream outputStream = null;

        try {

            outputStream = new FileOutputStream( file );

            for (Entry entry : properties) {
                outputStream.write( ( entry.first + " " + entry.second + "\n" ).getBytes() );
            }
        }
        finally {
            IOUtil.close( outputStream );
        }
    }

    private void commit() {

        List<String> cmd = new LinkedList<String>();
        cmd.add( "svn" );
        cmd.add( "commit" );
        cmd.add( "--depth" );
        cmd.add( "empty" );
        cmd.add( "." );
        cmd.add( "-m" );
        cmd.add( "\"[maven-svn-plugin] update svn:externals property\"" );
        execute( cmd );
    }
}
