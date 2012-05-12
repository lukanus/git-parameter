package net.uaznia.lukanus.hudson.plugins.gitparameter;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.ParameterValue;
import hudson.model.TaskListener;
import hudson.model.AbstractProject;
import hudson.model.Hudson;
import hudson.model.ParameterDefinition;
import hudson.model.ParametersDefinitionProperty;
import hudson.plugins.git.GitAPI;
import hudson.plugins.git.IGitAPI;
import hudson.plugins.git.Revision;
import hudson.plugins.git.GitSCM;
import hudson.scm.SCM;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;


public class GitParameterDefinition extends ParameterDefinition implements Comparable<GitParameterDefinition> {
	private static final long serialVersionUID = 9157832967140868122L;

	public static final String PARAMETER_TYPE_TAG = "PT_TAG";
	public static final String PARAMETER_TYPE_REVISION = "PT_REVISION";

	private final UUID uuid;

	@Extension
	public static class DescriptorImpl extends ParameterDescriptor {
		@Override
		public String getDisplayName() {
			return "Git Parameter";
		}
	}


	private String type;
	private String branch;
	private String tagFilter;
	private boolean useSmartNumberSort;

	private String errorMessage;        
	private String defaultValue;        

	private Map<String, String> revisionMap;
	private Map<String, String> tagMap;

	@DataBoundConstructor
	public GitParameterDefinition(String name,
			String type, String defaultValue,
			String description, String branch,
			String tagFilter, boolean useSmartNumberSort
			) {
		super(name, description);
		this.type = type;
		this.defaultValue = defaultValue;
		this.branch = branch;
		this.useSmartNumberSort = useSmartNumberSort;
		this.uuid = UUID.randomUUID();               

		if (isNullOrWhitespace(tagFilter)) {
			this.tagFilter = "*";
		} else {
			this.tagFilter = tagFilter;
		}

	}


	@Override
	public ParameterValue createValue(StaplerRequest request) {
		String value[] = request.getParameterValues(getName());
		if (value == null) {
			return getDefaultParameterValue();
		}
		return null;
	}

	@Override
	public ParameterValue createValue(StaplerRequest request, JSONObject jO) {
		Object value = jO.get("value");
		String strValue = "";
		if (value instanceof String) {
			strValue = (String)value;
		}
		else if (value instanceof JSONArray) {
			JSONArray jsonValues = (JSONArray)value;
			for(int i = 0; i < jsonValues.size(); i++) {
				strValue += jsonValues.getString(i);
				if (i < jsonValues.size() - 1) {
					strValue += ",";
				}
			}
		}

		if("".equals(strValue)) {
			strValue = defaultValue;
		}

		GitParameterValue gitParameterValue = new GitParameterValue(jO.getString("name"), strValue);
		return gitParameterValue;
	}

	@Override
	public ParameterValue getDefaultParameterValue() {
		String defValue = getDefaultValue();
		if (!StringUtils.isBlank(defValue)) {                    
			return new GitParameterValue(getName(), defValue);
		}
		return super.getDefaultParameterValue();
	}


	@Override
	public String getType() {
		return type;
	}


	public void setType(String type) {
		if(type.equals(PARAMETER_TYPE_TAG) || type.equals(PARAMETER_TYPE_REVISION) ) {
			this.type = type;
		} else {
			this.errorMessage = "wrongType";

		}
	}

	public String getBranch() {
		return this.branch;
	}

	public void setBranch(String nameOfBranch) {
		this.branch = nameOfBranch;
	}

	public boolean getUseSmartNumberSort() {
		return this.useSmartNumberSort;
	}

	public void setUseSmartNumberSort(boolean useSmartNumberSort) {
		this.useSmartNumberSort = useSmartNumberSort;
	}

	public String getTagFilter() {
		return this.tagFilter;
	}

	public void setTagFilter(String tagFilter) {
		this.tagFilter = tagFilter;
	}

	public String getDefaultValue() {
		return defaultValue;
	}

	public void setDefaultValue(String defaultValue) {
		this.defaultValue = defaultValue;
	}


	public AbstractProject<?,?> getParentProject() {
		AbstractProject<?,?> context = null;
		List<AbstractProject> jobs = Hudson.getInstance().getItems(AbstractProject.class);

		for(AbstractProject<?,?> project : jobs) {
			ParametersDefinitionProperty property = (ParametersDefinitionProperty) project.getProperty(ParametersDefinitionProperty.class);

			if(property != null) {
				List<ParameterDefinition> parameterDefinitions = property.getParameterDefinitions();

				if(parameterDefinitions != null) {
					for(ParameterDefinition pd : parameterDefinitions) {

						if(pd instanceof GitParameterDefinition && 
								((GitParameterDefinition) pd).compareTo(this) == 0) {

							context = project;
							break;
						}
					}
				}
			}
		}  

		return context;
	}

	@Override
	public int compareTo(GitParameterDefinition pd) {
		if(pd.uuid.equals(uuid)) {
			return 0;
		}

		return -1;
	}


	public void generateContents(String contenttype) {

		AbstractProject<?,?> project = getParentProject();


		// for (AbstractProject<?,?> project : Hudson.getInstance().getItems(AbstractProject.class)) {
			if (project.getSomeWorkspace() == null) {
				this.errorMessage = "noWorkspace";
			}                    

			SCM scm = project.getScm();

			//if (scm instanceof GitSCM); else continue;
			if (scm instanceof GitSCM) {
				this.errorMessage = "notGit";
			}


			GitSCM git = (GitSCM) scm;

			String defaultGitExe = File.separatorChar != '/' ? "git.exe" : "git";
			GitSCM.DescriptorImpl desc = (GitSCM.DescriptorImpl) git.getDescriptor();
			if (desc.getOldGitExe() != null) {
				defaultGitExe = desc.getOldGitExe();
			}

			EnvVars environment = null;

			try {
				environment = project.getSomeBuildWithWorkspace().getEnvironment(TaskListener.NULL);
			} catch(Exception e) {}

			for (RemoteConfig repository : git.getRepositories()) {
				for (URIish remoteURL : repository.getURIs()) {

					IGitAPI newgit = new GitAPI(defaultGitExe, project.getSomeWorkspace(), TaskListener.NULL, environment, new String());
					// for later use  
					//        if(this.branch != null && !this.branch.isEmpty()) {
						//          newgit.checkoutBranch(this.branch, null);
						//    }

					newgit.fetch();

					if(type.equalsIgnoreCase(PARAMETER_TYPE_REVISION)) {
						revisionMap = new LinkedHashMap<String, String>();


						List<ObjectId> oid;   

						if(this.branch != null && !this.branch.isEmpty()) {
							oid = newgit.revListBranch(this.branch);                        
						} else {
							oid = newgit.revListAll();                        
						}


						for(ObjectId noid: oid) {
							Revision r = new Revision(noid);
							List<String> test3 = newgit.showRevision(r);
							String[] authorDate = test3.get(3).split(">");
							String author = authorDate[0].replaceFirst("author ", "").replaceFirst("committer ", "") + ">";
							String goodDate = null;
							try {
								String totmp = authorDate[1].trim().split("\\+")[0].trim();
								long timestamp = Long.parseLong(totmp,10) * 1000;
								Date date = new Date();
								date.setTime(timestamp);

								goodDate = new SimpleDateFormat("yyyy:mm:dd").format(date);


							} catch (Exception e) {
								e.toString();
							}
							revisionMap.put(r.getSha1String(), r.getSha1String() + " " + author + " " + goodDate);
						}
					} else if(type.equalsIgnoreCase(PARAMETER_TYPE_TAG)) {   
						
						// use a LinkedHashMap so that keys are ordered as inserted
						tagMap = new LinkedHashMap<String, String>();

						Set<String> tagSet = newgit.getTagNames(tagFilter);
						ArrayList<String> sortedTagNames = sortTagNames(tagSet);
						Integer index = 0;
						for(String tagName: sortedTagNames) {
							tagMap.put(tagName, tagName);
							index += 1;
						}
					}                                

				}
			}
			//     }

	}

	public ArrayList<String> sortTagNames(Set<String> tagSet) {

		ArrayList<String> tags = new ArrayList<String>(tagSet);

		if (!this.getUseSmartNumberSort()) {
			Collections.sort(tags);
		} else {
			Collections.sort(tags, new SmartNumberStringComparer());
		}

		return tags;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public Map<String, String> getRevisionMap() {
		if( revisionMap == null || revisionMap.isEmpty()){
			generateContents(PARAMETER_TYPE_REVISION);
		}
		return revisionMap;
	}

	public Map<String, String> getTagMap() {
		if( tagMap == null || tagMap.isEmpty()){
			generateContents(PARAMETER_TYPE_TAG);
		}
		return tagMap;
	}

	private static boolean isNullOrWhitespace(String s) {
		return s == null || isWhitespace(s);

	}
	private static boolean isWhitespace(String s) {
		int length = s.length();
		for (int i = 0; i < length; i++) {
			if (!Character.isWhitespace(s.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Compares strings but treats a sequence of digits as a single character.
	 */
	static class SmartNumberStringComparer implements Comparator<String> {

		/**
		 * Gets the token starting at the given index.  It will return the first
		 * char if it is not a digit, otherwise it will return all consecutive digits
		 * starting at index.
		 * @param str The string to extract token from
		 * @param index The start location
		 */
		private String getToken(String str, int index) {
			char nextChar = str.charAt(index++);
			String token = String.valueOf(nextChar);
			
			// if the first char wasn't a digit then we're already done
			if (!Character.isDigit(nextChar))
				return token;
			
			// the first char was a digit so continue until end of string or non digit
			while (index < str.length()) {
				nextChar = str.charAt(index++);
				
				if (!Character.isDigit(nextChar))
					break;
				
				token += nextChar;
			}
			
			return token;
		}
		
		/**
		 * True if the string only contains digits
		 */
		private boolean stringContainsInteger(String str) {
			for (int charIndex = 0; charIndex < str.length(); charIndex++) {
				if (!Character.isDigit(str.charAt(charIndex)))
					return false;
			}
			return true;
		}
		
		public int compare(String a, String b) {
			
			int aIndex = 0;
			int bIndex = 0;
			
			while (aIndex < a.length() && bIndex < b.length()) {
				String aToken = getToken(a, aIndex);
				String bToken = getToken(b, bIndex);
				int difference;
				
				if (stringContainsInteger(aToken) && stringContainsInteger(bToken)) {
					int aInt = Integer.parseInt(aToken);
					int bInt = Integer.parseInt(bToken);
					difference = aInt - bInt;
				} else {
					difference = aToken.compareTo(bToken);
				}
				
				if (difference != 0)
					return difference;
				
				aIndex += aToken.length();
				bIndex += bToken.length();
			}

			return new Integer(a.length()).compareTo(new Integer(b.length()));
		}


	}
}
