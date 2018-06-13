package openiss.ws.rest;


import com.sun.jna.NativeLibrary;
import com.sun.xml.internal.messaging.saaj.util.ByteInputStream;
import openiss.Kinect;
import openiss.utils.OpenISSConfig;
import openiss.utils.OpenISSImageDriver;
import openiss.utils.PATCH;
import openiss.ws.soap.endpoint.ServicePublisher;
import org.glassfish.jersey.media.multipart.*;
import org.glassfish.jersey.media.multipart.file.FileDataBodyPart;
import org.glassfish.jersey.media.multipart.file.StreamDataBodyPart;

import javax.activation.MimetypesFileTypeMap;
import javax.imageio.ImageIO;
import javax.ws.rs.*;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;

@Path("/openiss")
public class OpenISSRestService {


    static String mixFlag = "default";
    static boolean cannyFlag = false;
    static boolean contourFlag = false;
    static OpenISSImageDriver driver;

    static {
        driver = new OpenISSImageDriver();
        String PROJECT_HOME = System.getProperty("user.dir");
        int arch = Integer.parseInt(System.getProperty("sun.arch.data.model"));
        String osName = System.getProperty("os.name").toLowerCase();

        if (OpenISSConfig.USE_OPENCV) {
            if(osName.indexOf("win") >= 0) {
                System.out.println(arch + " windows");
                System.load(PROJECT_HOME+"\\lib\\opencv\\win\\x64\\opencv_java341.dll");
            }
            else if(osName.indexOf("mac") >= 0){
                System.out.println("Loading Native library" + PROJECT_HOME+"/lib/opencv/mac/libopencv_java341.dylib");
                System.load(PROJECT_HOME+"/lib/opencv/mac/libopencv_java341.dylib");
            }
        }

    }

    /**
     * Method handling HTTP GET requests. The returned object will be sent
     * to the client as "text/plain" media type.
     *
     * @return String that will be returned as a text/plain response.
     */
    @GET
    @Path("hello")
    @Produces(MediaType.TEXT_PLAIN)
    public String getIt() {
        return "Hello World from Jersey API!";
    }

    @GET
    @Path("/{type}")
    @Produces("image/*")
    public Response getImage(@PathParam(value = "type") String type) {

        ResponseBuilder response;
        byte[] image = new byte[0];
        // validity checks
        if (!type.equals("color") && !type.equals("depth")) {
            return Response.noContent().build();
        }

        if (type.equals("color")) {
            image = driver.getFrame("color");
        } else {
            image = driver.getFrame("depth");
        }
        response = Response.ok(pipelineImage(image, type), "image/jpeg");

        return response.build();
    }

    @PATCH
    @Path("/mix/{action}")
    @Produces("text/plain")
    /**
     * the GET images will be mixed with depth, rgb or canny
     */
    public String enableMix(@PathParam(value = "action") String action) {
        if (action.equals("depth") || action.equals("color") || action.equals("canny")) {
            mixFlag = action;
        }
        return getFlags();
    }


    @DELETE
    @Path("/mix")
    @Produces("text/plain")
    public String disableMix() {
        mixFlag = "default";
        return getFlags();
    }

    @PATCH
    @Path("/opencv/{type}")
    @Produces("text/plain")
    public String enableOpenCV(@PathParam(value = "type") String type) {

        // validity checks
        if (!OpenISSConfig.USE_OPENCV || (!type.equals("canny") && !type.equals("contour"))) {
            return "Service not supported";
        }

        if (type.equals("canny")) {
            cannyFlag = true;
        } else if (type.equals("contour")) {
            contourFlag = true;
        }
        return getFlags();
    }


    @DELETE
    @Path("/opencv/{type}")
    @Produces("text/plain")
    public String disableOpenCV(@PathParam(value = "type") String type) {

        // validity checksX
        if (!OpenISSConfig.USE_OPENCV || (!type.equals("canny") && !type.equals("contour"))) {
            return "Service not supported.";
        }
        if (type.equals("canny")) {
            cannyFlag = false;
        } else if (type.equals("contour")) {
            contourFlag = false;
        }
        return getFlags();
    }


    @GET
    @Path("reqmix/{addr}{port}")
    @Produces(MediaType.TEXT_PLAIN)
    public String requestMix(
            @PathParam(value = "addr") String addr, @PathParam(value = "port") String port) {
        byte[] image = driver.getFrame("color");

        String serverURL = "http://" + addr + ":" + port + "/rest/openiss/upload";
        System.out.println("Server URL: " + serverURL);

        String result = "http://" + addr + ":" + port + "/rest/openiss/mix/result";

        Client client = ClientBuilder.newBuilder().
                register(MultiPartFeature.class).build();
        WebTarget server = client.target(serverURL);

        MultiPart multiPart = new MultiPart();
        StreamDataBodyPart body = new StreamDataBodyPart("file", new ByteInputStream(image, image.length));
        multiPart.bodyPart(body);

        Response response = server.request(MediaType.TEXT_PLAIN)
                .post(Entity.entity(multiPart, "image/jpeg"));
        if (response.getStatus() == 200) {
            return response.readEntity(String.class);
        } else {
            return ("Response is not ok");
        }
    }

    boolean canMix = false;
    String mixImgName;

    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadFile(FormDataMultiPart form) throws IOException {
        System.out.println("receive something ...");

        FormDataBodyPart filePart = form.getField("file");
        ContentDisposition headerOfFilePart =  filePart.getContentDisposition();
        InputStream fileInputStream = filePart.getValueAs(InputStream.class);
//        mixImgName = headerOfFilePart.getFileName();

        mixImgName = "wait_for_mix.jpg";
        writeToFile(fileInputStream, mixImgName);

        byte[] image = Files.readAllBytes(new File(mixImgName).toPath());
        ResponseBuilder response = Response.ok(pipelineImage(image, "usermix"), "image/jpeg");
        return response.build();


//        // save the file to the server
//        writeToFile(fileInputStream, mixImgName);
//        String output = "File saved to server location using FormDataMultiPart : " + mixImgName;
//        return Response.status(200).entity(output).build();

    }

    // save uploaded file to new location
    private void writeToFile(InputStream uploadedInputStream, String uploadedFileLocation) {
        try {
            OutputStream out;
            int read = 0;
            byte[] bytes = new byte[1024];

            out = new FileOutputStream(new File(uploadedFileLocation));
            while ((read = uploadedInputStream.read(bytes)) != -1) {
                out.write(bytes, 0, read);
            }
            out.flush();
            out.close();
        } catch (IOException e) {

            e.printStackTrace();
        }
    }

    private String getFlags() {
        String flags = "Mix: " + String.valueOf(mixFlag) +
                "\nCanny: " + String.valueOf(cannyFlag) +
                "\nContour: " + String.valueOf(contourFlag);
        return flags;
    }

    private byte[] pipelineImage(byte[] image, String baseImage) {

        byte[] processedImage = image;

        if (mixFlag.equals("depth")) {
            //@TODO this needs to be fixed conditionally to work with static image and driver
            processedImage = driver.mixFrame(image, "depth", "+");
        } else if (mixFlag.equals("color")) {
            processedImage = driver.mixFrame(image, "color", "+");
        } else if (mixFlag.equals("canny")) {
            // todo: add docanny support
            // mix with do canny

            if(baseImage.equals("depth")) {
                processedImage = driver.mixFrame(driver.doCanny(image), "depth", "+");
            } else {
                processedImage = driver.mixFrame(driver.doCanny(image), "color", "+");
            }
        }


        if (cannyFlag) {
            System.out.println("Running driver.doCanny");
        	processedImage = driver.doCanny(processedImage);
        }

        if (contourFlag) {
            System.out.println("Running driver.contour");
        	processedImage = driver.contour(image);
        }

        if ("usermix".equals(baseImage)) {
            processedImage = driver.mixFrame(image, "color", "+");
        }

        return processedImage;
    }


}