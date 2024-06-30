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

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;

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
	private static final String DIST_DIR = "dist";
	private static final String SQS_URL = "sqs.url";
    private static final Region REGION = Region.AP_SOUTH_1;
    private static final String BUILD = "build";

	
	private static String awsAccessKey = "";
	private static String secretAccessKey = "";
	private static String breakPoint	= "HESOYAM";
	private static Properties properties = new Properties();
	private static AwsCredentials credentials = null;
	private static S3AsyncClient s3AsyncClient = null;
	private static S3TransferManager transferManager = null;
	private static AmazonSQS amazonSQS = null;
	private static String sqsUrl = "";

	
	/**
	 * Runs an infinite loop and keeps polling for deploymentIds from upload queue to start with deployment.
	 * @throws InterruptedException 
	 */
	public static void startDeployment() throws InterruptedException {
		LOGGER.info("Entered inside startDeployment()");
		while(true) {
			try {
				List<String> listOfUniqueIds = getDeploymentIdFromQueue();
				if(CollectionUtils.isEmpty(listOfUniqueIds)) continue;
				
				LOGGER.info("List of Ids polled from queue : " + listOfUniqueIds.size());

				breakPointForInfiniteLoop(listOfUniqueIds);
				
				listOfUniqueIds.forEach(uniqueId -> {
					
					//Download
					Boolean isProjectDownloaded = downloadProjectFromS3(uniqueId);
					LOGGER.info("Project Download Status : " + isProjectDownloaded);
					//updateStatus when Table implemented
					if(!Boolean.TRUE.equals(isProjectDownloaded)) return;
					
					//Build and Deploy
					Boolean isProjectBuilt = buildDownloadedProject(uniqueId);
					LOGGER.info("Project Build status : ", isProjectBuilt);
					//updateStatus when Table implemented
					if(!Boolean.TRUE.equals(isProjectDownloaded)) return;
					
					
					//Upload build files to S3 
					String localFolderPath = getLocalPath(uniqueId);
					int noOfFilesUploaded = uploadDistFolderToS3(localFolderPath, DIST_DIR + "/" + uniqueId);
					LOGGER.info("No. of file uploaded: ", noOfFilesUploaded);
				});
				
			} catch(Exception e){
				LOGGER.error("Exception occured in startDeployment()", e);
				Thread.sleep(10000);
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
		File distFolderCheck = new File(localFilePath + File.separator + DIST_DIR);
		if(null != distFolderCheck && distFolderCheck.exists()) {
			return localFilePath + File.separator + DIST_DIR;
		}
		return localFilePath + File.separator + BUILD;
	}


	/**
	 * Polls the upload queue and returns the 10 top-most uniqueId for deployment.
	 * @return unqiueDeploymentId
	 * @throws Exception 
	 */
	public static List<String> getDeploymentIdFromQueue() throws Exception {
		LOGGER.info("Entered inside getDeploymentIdFromQueue()");
		List<String> listOfUniqueIds = new ArrayList<>();
		ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest()
                .withQueueUrl(sqsUrl)
                .withWaitTimeSeconds(20)  
                .withMaxNumberOfMessages(10);
		try {
			ReceiveMessageResult receiveMessageResult = amazonSQS.receiveMessage(receiveMessageRequest);
			List<Message> listOfMessages =  receiveMessageResult.getMessages();
			if(!CollectionUtils.isEmpty(listOfMessages)) {
				listOfMessages.forEach(message -> {
					LOGGER.info("Polled message: ", message.getBody());
					listOfUniqueIds.add(message.getBody());
					amazonSQS.deleteMessage(new DeleteMessageRequest(sqsUrl,message.getReceiptHandle()));
				});
			}
		}catch(Exception ex){
			LOGGER.error("Exception occured while polling from Queue. ", ex);
			throw new Exception("Exception occured while polling from Queue. ", ex);
		}
		return listOfUniqueIds;
	}
	
	
	/**
	 * Custom break point mechanism to stop the Infinite loop (if required) from Queue.
	 * @param idFromQueue
	 * @throws Exception
	 */
	public static void breakPointForInfiniteLoop(List<String> listOfUniqueIds) throws Exception{
		LOGGER.info("Entered inside breakPointForInfiniteLoop(), with no. of Ids: ", listOfUniqueIds.size());
		if(listOfUniqueIds.stream().anyMatch(uniqueId -> breakPoint.equalsIgnoreCase(uniqueId))) {
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
		Boolean isDownloaded = Boolean.FALSE;
		int noOfFailedFiles = 0;
		Instant start = Instant.now();
        
        try {
        	File projectFolder = folderCreation();
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
            
            isDownloaded = Boolean.TRUE;
        } catch (Exception ex) {
            LOGGER.error("S3 exception occured while uploading object.",ex);
        }finally{
        	Instant end = Instant.now();
        	Duration timeTaken = Duration.between(start,end);
        	LOGGER.info("Time taken to download the files from S3: "+ timeTaken.toMillis() + " ms");
        }
		return isDownloaded;
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
//			List<String> installCommand = Arrays.asList("npm", "install", "react-scripts");
//			createAndRunProcess(projectPath, installCommand);

			List<String> buildCommand = Arrays.asList("npm", "run", "build");
			createAndRunProcess(projectPath, buildCommand);
			isProjectBuilt = Boolean.TRUE;
		}catch(Exception e) {
			LOGGER.error("Exception occured in buildDownloadedProject() ", e);
		}
		return isProjectBuilt;
	}


	/**
	 * Method to execute command in terminal
	 * @param outputFolder
	 * @param buildCommand
	 * @throws IOException
	 */
	private static void createAndRunProcess(String filePath, List<String> buildCommand) throws IOException {
		LOGGER.info("Entered inside createAndRunProcess()");

		File project = new File(filePath);
		Process process = new ProcessBuilder(buildCommand).redirectErrorStream(true).directory(project).start();
		
		try {
		    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
		    String line;
		    LOGGER.info("npm build output:: ");
		    while ((line = reader.readLine()) != null) {
		    	LOGGER.info(line);
		    }
		    int exitCode = process.waitFor();
		    LOGGER.info("Exit Code: " + exitCode);
		} catch (IOException | InterruptedException e) {
			LOGGER.error("Exception while running npm commands");
		}
		
		LOGGER.info("Exiting createAndRunProcess() method()");
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
			breakPoint = (null != properties.getProperty(BREAK_POINT))
					?properties.getProperty(BREAK_POINT):breakPoint;
			sqsUrl = properties.getProperty(SQS_URL);

			initializeAWSCreds();
			return;
		}
		LOGGER.info("Unable to fetch credentials.properties file path from env variable.");
	}
	
	
	/**
	 * Initialize all required AWS context for faster uploads
	 */
	private static void initializeAWSCreds() {
		LOGGER.info("Initializing Aws context");
		try {
			credentials = AwsBasicCredentials.create(awsAccessKey, secretAccessKey);
			s3AsyncClient = S3AsyncClient.builder()
	                .credentialsProvider(StaticCredentialsProvider.create(credentials))
	                .region(REGION)
	                .build();
			transferManager = S3TransferManager.builder()
                    .s3Client(s3AsyncClient)
                    .build();
			amazonSQS =  AmazonSQSClient.builder()
			            .withRegion(REGION.toString())
			            .withCredentials(new AWSStaticCredentialsProvider( new BasicAWSCredentials(awsAccessKey,secretAccessKey)))
			            .build();
		} catch (Exception e) {
			LOGGER.error("Exception occured while initializing AWS context for S3 and SQS", e);
		}
		LOGGER.info("Exit from initializeAWSCreds()");
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
        int noOfFilesUploaded = 0;
        
        try {
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
        	LOGGER.info("Time taken to upload the files to S3: "+ timeTaken.toMillis() + " ms");
        }
		return noOfFilesUploaded;
	}
}
