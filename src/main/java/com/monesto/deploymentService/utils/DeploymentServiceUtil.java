package com.monesto.deploymentService.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.config.DownloadFilter;
import software.amazon.awssdk.transfer.s3.model.CompletedDirectoryDownload;
import software.amazon.awssdk.transfer.s3.model.CompletedDirectoryUpload;
import software.amazon.awssdk.transfer.s3.model.DirectoryDownload;
import software.amazon.awssdk.transfer.s3.model.DirectoryUpload;
import software.amazon.awssdk.transfer.s3.model.DownloadDirectoryRequest;
import software.amazon.awssdk.transfer.s3.model.UploadDirectoryRequest;
import software.amazon.awssdk.utils.StringUtils;

public class DeploymentServiceUtil {
	private static final Logger LOGGER = LoggerFactory.getLogger(DeploymentServiceUtil.class);
	private static final String CREDS_FILE_KEY = "CREDS_VAL";
	private static final String CREDS_FILE_NAME = "credentials.properties";
	private static final String S3_ACCESS_KEY = "s3AccessKey";
	private static final String S3_SECRET_ACCESS_KEY = "s3SecretAccessKey";
	private static final String BUCKET_NAME = "varcel-upload-service";
	private static final String BREAK_POINT	= "breakPoint";
	private static final String OUTPUT_DIR = "output";
	private static final String S3_OUTPUT_DIR = "varcel-output";
	private static final String REDIS_URL = "redis.url";
	private static final String REDIS_PORT = "redis.port";
	private static final String DIST_DIR = "dist";
	
	private static String awsAccessKey = "";
	private static String secretAccessKey = "";
	private static String redisUrl = "";
	private static Integer redisPort = 0;
	private static String breakPoint	= "HESOYAM";
	private static Properties properties = new Properties();

	
	/**
	 * Runs an infinite loop and keeps polling for deploymentIds from upload queue to start with deployment.
	 */
	public static void startDeployment() {
		LOGGER.info("Entered inside startDeployment()");
		while(true) {
			try {
				List<String> uniqueDeploymentId = getDeploymentIdFromQueue();
				if(CollectionUtils.isEmpty(uniqueDeploymentId) || uniqueDeploymentId.size() < 2) continue;
				
				LOGGER.info("UniqueId fetched: "+ uniqueDeploymentId.get(1)+" From queue: "+ uniqueDeploymentId.get(0));

				breakPointForInfiniteLoop(uniqueDeploymentId.get(1));
				Boolean isProjectDownloaded = downloadProjectFromS3(uniqueDeploymentId.get(1));
				LOGGER.info("Project Download Status : " + isProjectDownloaded);
				//updateStatus when Table implemented
				if(!Boolean.TRUE.equals(isProjectDownloaded)) break;
				
				Boolean isProjectBuilt = buildDownloadedProject(uniqueDeploymentId.get(1));
				LOGGER.info("Project Build status : ", isProjectBuilt);
				//updateStatus when Table implemented
				if(!Boolean.TRUE.equals(isProjectDownloaded)) continue;
				String localFolderPath = getLocalPath(uniqueDeploymentId.get(1));

				int noOfFilesUploaded = uploadDistFolderToS3(localFolderPath, DIST_DIR + "/" + uniqueDeploymentId.get(1));
				
				
			} catch(Exception e){
				LOGGER.error("Exception occured in startDeployment()", e);
			}
		}
	}
	
	
	/**
	 * Check if compiled folder exists or not, as in case of Raw HMTL/CSS/JS project no compilation is required.
	 * @param projectId
	 * @return localFilePath
	 */
	private static String getLocalPath(String projectId) {
		LOGGER.info("Entered inside getLocalPath");
		File outputFolder = folderCreation();
		String localFilePath = outputFolder.getAbsolutePath() + File.separator + S3_OUTPUT_DIR + File.separator + projectId;
		File reactCheck = new File(localFilePath + File.separator + DIST_DIR);
		if(null != reactCheck && reactCheck.exists()) {
			return localFilePath + File.separator + DIST_DIR;
		}
		return localFilePath;
	}


	/**
	 * Polls the upload queue and returns the top-most uniqueId for deployment.
	 * @return unqiueDeploymentId
	 * @throws InterruptedException 
	 */
	public static List<String> getDeploymentIdFromQueue() throws InterruptedException {
		LOGGER.info("Entered inside getDeploymentIdFromQueue()");
		List<String> uniqueDeploymentId = new ArrayList<>();
		try (Jedis jedis = new JedisPool(redisUrl,redisPort).getResource()){
			uniqueDeploymentId = jedis.brpop(0, BUCKET_NAME);
		}catch (Exception ex) {
			LOGGER.error("Exception occured in addProcessToQueue(): ", ex);
			Thread.sleep(5000);
		}
		return uniqueDeploymentId;
	}
	
	
	/**
	 * Custom break point mechanism to stop the Infinite loop (if required) from Queue.
	 * @param idFromQueue
	 * @throws Exception
	 */
	public static void breakPointForInfiniteLoop(String idFromQueue) throws Exception{
		LOGGER.info("Entered inside breakPointForInfiniteLoop(), with Id: ", idFromQueue);
		if(breakPoint.equalsIgnoreCase(idFromQueue)) {
			throw new Exception("Reached Break point hence stopping the deployment loop.");
		}
		LOGGER.info("Exiting from breakPointForInfiniteLoop()");
	}


	/**
	 * Download the projects folder from S3.
	 * @param deploymentId
	 * @return
	 */
	public static Boolean downloadProjectFromS3(String deploymentId) {
		LOGGER.info("Entered into downloadProjectFromS3 ");
		Boolean isSavedFlag = Boolean.FALSE;
		int noOfFailedFiles = 0;
		Instant start = Instant.now();
		AwsCredentials credentials = AwsBasicCredentials.create(awsAccessKey, secretAccessKey);
        Region region = Region.AP_SOUTH_1;
        S3AsyncClient s3AsyncClient = S3AsyncClient.builder()
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(region)
                .build();
        
        try {
        	File projectFolder = folderCreation();
        	
            S3TransferManager transferManager = S3TransferManager.builder()
                    .s3Client(s3AsyncClient)
                    .build();
            String folderPrefix = S3_OUTPUT_DIR + "/" + deploymentId;
            DownloadFilter folderToDownload = s3Object -> (s3Object.key().startsWith(folderPrefix));
            DirectoryDownload directoryDownload = transferManager.downloadDirectory(DownloadDirectoryRequest.builder()
                    .destination(Paths.get(projectFolder.getAbsolutePath()))
                    .bucket(BUCKET_NAME).filter(folderToDownload)
                    .build());
            CompletedDirectoryDownload completedDirectoryDownload = directoryDownload.completionFuture().join();

            completedDirectoryDownload.failedTransfers()
                    .forEach(fail -> LOGGER.warn("Object [{}] failed to transfer", fail.toString()));
            noOfFailedFiles =  completedDirectoryDownload.failedTransfers().size();
            LOGGER.info("Failed to download " + noOfFailedFiles + " files from bucket " + BUCKET_NAME);
            
            isSavedFlag = Boolean.TRUE;
        } catch (Exception ex) {
            LOGGER.error("S3 exception occured while uploading object.",ex);
        }finally{
        	Instant end = Instant.now();
        	Duration timeTaken = Duration.between(start,end);
        	LOGGER.info("Time taken to download the files from S3: "+ timeTaken.toString() + " ms");
        	s3AsyncClient.close();
        }
		return isSavedFlag;
	}


	/**
	 * Creates Required folder structure
	 * @return
	 */
	private static File folderCreation() {
		File output = new File(OUTPUT_DIR);
		if(!output.exists()) {
			LOGGER.info("Creating " + OUTPUT_DIR + "folder.");
			output.mkdir();
		}
		return output;
	}


	
	public static Boolean buildDownloadedProject(String uniqueDeploymentId) {
		LOGGER.info("Entered inside buildDownloadedProject()");
		Boolean isProjectBuilt = Boolean.FALSE;
		try {
			File outputFolder = folderCreation();
			String projectPath = outputFolder.getAbsolutePath()+ File.separator + S3_OUTPUT_DIR + File.separator + uniqueDeploymentId;
			List<String> installCommand = Arrays.asList("npm", "install", "react-scripts");
			createAndRunProcess(projectPath, installCommand);

			List<String> runCommand = Arrays.asList("npm", "run", "build");
			createAndRunProcess(projectPath, runCommand);
		}catch(Exception e) {
			LOGGER.error("Exception occured in buildDownloadedProject() ", e);
		}
		return isProjectBuilt;
	}


	/**
	 * @param outputFolder
	 * @param buildCommand
	 * @throws IOException
	 */
	private static void createAndRunProcess(String filePath, List<String> buildCommand) throws IOException {
		LOGGER.info("Entered inside createAndRunProcess()");

		File project = new File(filePath);
		Process process = new ProcessBuilder(buildCommand).redirectErrorStream(true).directory(project).start();
		StringBuilder commandOutput = new StringBuilder();
		
		try {
		    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
		    String line;
		    while ((line = reader.readLine()) != null) {
		        System.out.println(line);
		    }
		    int exitCode = process.waitFor();
		    System.out.println("Exit Code: " + exitCode);
		} catch (IOException | InterruptedException e) {
			LOGGER.error("Exception while running npm commands");
		}
		
		LOGGER.info("npm build output:: ", commandOutput.toString());
	}


	private static void createCompiledFileDir(String uniqueDeploymentId) {
		//File 
		
	}


	/**
	 * Fetches path to credential.properties from environment variable and initialize the credentials 
	 * for Database, S3 etc.<br>
	 * Add an Environment variable in launch configurations as <b>CREDS_VAL</b> having value as path 
	 * to <b>credentials.properties</b> file.
	 */
	public static void initilizeCredentials() {
		LOGGER.info("Entered inside initilizeCredentials().");
		String credsPath = (StringUtils.isBlank(System.getenv(CREDS_FILE_KEY)))
				?System.getProperty(CREDS_FILE_KEY):System.getenv(CREDS_FILE_KEY);
		LOGGER.info("Credentials file path : " + credsPath);
		if(StringUtils.isNotBlank(credsPath)) {
			FileInputStream credentialsReader = null;
			try {
				credentialsReader = new FileInputStream(credsPath + CREDS_FILE_NAME);
				if(null != credentialsReader) properties.load(credentialsReader);
			}catch(Exception ex) {
				LOGGER.error("Exception occured while initiliazing credentials. ", ex);
			}finally {
				closeInputStream(credentialsReader);
			}
			
			awsAccessKey = properties.getProperty(S3_ACCESS_KEY);
			secretAccessKey = properties.getProperty(S3_SECRET_ACCESS_KEY);
			redisUrl = properties.getProperty(REDIS_URL);
			redisPort = (null != properties.getProperty(REDIS_PORT))
					?Integer.parseInt(properties.getProperty(REDIS_PORT)):0;
			breakPoint = (null != properties.getProperty(BREAK_POINT))
					?properties.getProperty(BREAK_POINT):breakPoint;
			return;
		}
		LOGGER.info("Unable to fetch credentials.properties file path from env variable.");
	}
	
	
	/**
	 * static method to close input stream if not null.
	 * @param fileInputStream
	 */
	private static void closeInputStream(FileInputStream fileInputStream) {
		try {
			if(null != fileInputStream) {
				fileInputStream.close();
			}
		} catch(Exception ex) {
			LOGGER.error("Exception occured while closing the fileInputStream.",ex);
		}
	}

	
	/**
	 * Function to upload downloaded objects to S3 bucket.
	 * @param localLocation
	 * @param bucketLocation
	 * @return noOfFilesUploaded
	 */
	public static int uploadDistFolderToS3(String localLocation, String bucketLocation) {
		LOGGER.info("Entered into uploadDistFolderToS3 ");
		Instant start = Instant.now();
		AwsCredentials credentials = AwsBasicCredentials.create(awsAccessKey, secretAccessKey);
        Region region = Region.AP_SOUTH_1;
        int noOfFilesUploaded = 0;
        S3AsyncClient s3AsyncClient = S3AsyncClient.builder()
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(region)
                .build();
        
        try {
            S3TransferManager transferManager = S3TransferManager.builder()
                    .s3Client(s3AsyncClient)
                    .build();
            
            DirectoryUpload directoryUpload = transferManager.uploadDirectory(UploadDirectoryRequest.builder()
                    .source(Paths.get(localLocation))
                    .bucket(BUCKET_NAME).s3Prefix(bucketLocation)
                    .build());
            long noOfFiles = Files.walk(Paths.get(localLocation))
                    .filter(p -> p.toFile().isFile())
                    .mapToLong(p -> p.toFile().length())
                    .sum();
            CompletedDirectoryUpload completedDirectoryUpload = directoryUpload.completionFuture().join();
            completedDirectoryUpload.failedTransfers()
                    .forEach(fail -> LOGGER.warn("Object [{}] failed to transfer", fail.toString()));
            noOfFilesUploaded =   (int) noOfFiles - completedDirectoryUpload.failedTransfers().size();
            int noOfFilesfailed =  completedDirectoryUpload.failedTransfers().size();
            LOGGER.info("Successfully placed " + noOfFilesUploaded + " files into bucket " + BUCKET_NAME);
            LOGGER.info("Failed to place " + noOfFilesfailed + " files into bucket " + BUCKET_NAME);

        } catch (Exception ex) {
            LOGGER.error("S3 exception occured while uploading object.",ex);
        }finally{
        	Instant end = Instant.now();
        	Duration timeTaken = Duration.between(start,end);
        	LOGGER.info("Time taken to upload the files to S3: "+ timeTaken.toString() + " ms");
        	s3AsyncClient.close();
        }
		return noOfFilesUploaded;
	}
}
