package org.xnatural.enet.test.rest.request;

import org.jboss.resteasy.annotations.providers.multipart.PartFilename;
import org.jboss.resteasy.annotations.providers.multipart.PartType;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.xnatural.enet.test.rest.BasePojo;
import org.xnatural.enet.test.rest.FileData;

import javax.ws.rs.FormParam;
import java.io.IOException;
import java.io.InputStream;

public class AddFileDto extends BasePojo {
    @FormParam("name")
    @PartType("text/plan")
    private String name;
    @FormParam("age")
    @PartType("text/plan")
    private Integer age;
    @FormParam("file")
    @PartType("application/oc")
    // @PartFilename
    private InputPart inputPart;
    private FileData headportrait;



    public String getName() {
        return name;
    }


    public AddFileDto setName(String name) {
        this.name = name;
        return this;
    }


    public Integer getAge() {
        return age;
    }


    public AddFileDto setAge(Integer age) {
        this.age = age;
        return this;
    }


    public FileData getHeadportrait() {
        if (headportrait != null) return headportrait;
        headportrait = new FileData();
        InputStream inputStream = null;
        try {
            inputStream = inputPart.getBody(InputStream.class, null);
            if (inputStream.available() < 1) return headportrait;
            String fileNameHeader = inputPart.getHeaders().get("Content-Disposition").get(0).replace("\"", "");
            String fileName = (fileNameHeader.contains("filename") ? fileNameHeader.split("filename=")[1] : (fileNameHeader.contains("name") ? fileNameHeader.split("name=")[1] : ""));
            String[] fileNameArr = fileName.split("\\.");

            // fileDto.setFileData(IOUtils.toByteArray(inputStream));
            headportrait.setInputStream(inputStream);
            headportrait.setOriginName(fileName);
            headportrait.setExtension(fileNameArr.length > 1 ? fileNameArr[1] : "");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return headportrait;
    }
}
