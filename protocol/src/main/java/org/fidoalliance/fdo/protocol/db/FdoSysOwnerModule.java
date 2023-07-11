// Copyright 2022 Intel Corporation
// SPDX-License-Identifier: Apache 2.0

package org.fidoalliance.fdo.protocol.db;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.util.ClientBuilder;
// import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.KubeConfig;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Blob;
import java.sql.SQLException;
import java.util.Date;
import java.util.Map;
import java.util.Objects;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.fidoalliance.fdo.protocol.Config;
import org.fidoalliance.fdo.protocol.HttpClientSupplier;
import org.fidoalliance.fdo.protocol.InternalServerErrorException;
import org.fidoalliance.fdo.protocol.LoggerService;
import org.fidoalliance.fdo.protocol.Mapper;
import org.fidoalliance.fdo.protocol.dispatch.ServiceInfoModule;
import org.fidoalliance.fdo.protocol.dispatch.ServiceInfoSendFunction;
import org.fidoalliance.fdo.protocol.entity.SystemPackage;
import org.fidoalliance.fdo.protocol.entity.SystemResource;
import org.fidoalliance.fdo.protocol.message.AnyType;
import org.fidoalliance.fdo.protocol.message.DevModList;
import org.fidoalliance.fdo.protocol.message.EotResult;
import org.fidoalliance.fdo.protocol.message.ServiceInfoKeyValuePair;
import org.fidoalliance.fdo.protocol.message.ServiceInfoModuleState;
import org.fidoalliance.fdo.protocol.message.ServiceInfoQueue;
import org.fidoalliance.fdo.protocol.message.StatusCb;
import org.fidoalliance.fdo.protocol.serviceinfo.DevMod;
import org.fidoalliance.fdo.protocol.serviceinfo.FdoSys;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.yaml.snakeyaml.Yaml;

/**
 * Implements FdoSysModule spec.
 */
public class FdoSysOwnerModule implements ServiceInfoModule {


  private final LoggerService logger = new LoggerService(FdoSysOwnerModule.class);

  @Override
  public String getName() {
    return FdoSys.NAME;
  }

  @Override
  public void prepare(ServiceInfoModuleState state) throws IOException {
    state.setExtra(AnyType.fromObject(new FdoSysModuleExtra()));
  }

  @Override
  public void receive(ServiceInfoModuleState state, ServiceInfoKeyValuePair kvPair)
      throws IOException {
    FdoSysModuleExtra extra = state.getExtra().covertValue(FdoSysModuleExtra.class);
    switch (kvPair.getKey()) {
      case DevMod.KEY_MODULES: {
        DevModList list =
            Mapper.INSTANCE.readValue(kvPair.getValue(), DevModList.class);
        for (String name : list.getModulesNames()) {
          if (name.equals(FdoSys.NAME)) {
            state.setActive(true);
            ServiceInfoQueue queue = extra.getQueue();
            ServiceInfoKeyValuePair activePair = new ServiceInfoKeyValuePair();
            activePair.setKeyName(FdoSys.ACTIVE);
            activePair.setValue(Mapper.INSTANCE.writeValue(true));
            queue.add(activePair);

          }
        }
      }
      break;
      case DevMod.KEY_DEVICE:
      case DevMod.KEY_OS:
      case DevMod.KEY_VERSION:
      case DevMod.KEY_ARCH:
        extra.getFilter().put(kvPair.getKey(),
            Mapper.INSTANCE.readValue(kvPair.getValue(), String.class));
        break;
      case FdoSys.STATUS_CB:
        if (state.isActive()) {
          StatusCb status = Mapper.INSTANCE.readValue(kvPair.getValue(), StatusCb.class);

          //send notification of status
          ServiceInfoKeyValuePair kv = new ServiceInfoKeyValuePair();
          kv.setKeyName(FdoSys.STATUS_CB);
          kv.setValue(Mapper.INSTANCE.writeValue(status));
          extra.getQueue().add(kv);
          onStatusCb(state, extra, status);
          if (status.isCompleted()) {
            // check for error
            if (status.getRetCode() != 0) {
              throw new InternalServerErrorException("Exec_cb status returned failure.");
            }
            extra.setWaiting(false);
            extra.getQueue().addAll(extra.getWaitQueue());
            extra.setWaitQueue(new ServiceInfoQueue());
          }
        }
        break;
      case FdoSys.DATA:
        if (state.isActive()) {
          byte[] data = Mapper.INSTANCE.readValue(kvPair.getValue(), byte[].class);
          onFetch(state, extra, data);
        }
        break;
      case FdoSys.EOT:
        if (state.isActive()) {
          extra.setWaiting(false);
          extra.setQueue(extra.getWaitQueue());
          extra.setWaitQueue(new ServiceInfoQueue());
          EotResult result = Mapper.INSTANCE.readValue(kvPair.getValue(), EotResult.class);
          onEot(state, extra, result);
        }
        break;
      default:
        break;
    }
    state.setExtra(AnyType.fromObject(extra));
  }

  @Override
  public void send(ServiceInfoModuleState state, ServiceInfoSendFunction sendFunction)
      throws IOException {

    FdoSysModuleExtra extra = state.getExtra().covertValue(FdoSysModuleExtra.class);

    if (!extra.isLoaded() && infoReady(extra)) {
      load(state, extra);
      extra.setLoaded(true);
    }

    while (extra.getQueue().size() > 0) {
      boolean sent = sendFunction.apply(extra.getQueue().peek());
      if (sent) {
        if (extra.getQueue().size() > 0) {
          checkWaiting(extra, Objects.requireNonNull(extra.getQueue().poll()));
        }
      } else {
        break;
      }
      if (extra.isWaiting()) {
        break;
      }
    }
    if (extra.getQueue().size() == 0 && !extra.isWaiting()) {
      state.setDone(true);
    }
    state.setExtra(AnyType.fromObject(extra));
  }

  protected void checkWaiting(FdoSysModuleExtra extra, ServiceInfoKeyValuePair kv) {
    switch (kv.getKey()) {
      case FdoSys.EXEC_CB:
      case FdoSys.FETCH:
        extra.setWaiting(true);
        extra.setWaitQueue(extra.getQueue());
        extra.setQueue(new ServiceInfoQueue());
        break;
      default:
        break;
    }
  }

  protected boolean infoReady(FdoSysModuleExtra extra) {
    return extra.getFilter().containsKey(DevMod.KEY_DEVICE)
        && extra.getFilter().containsKey(DevMod.KEY_OS)
        && extra.getFilter().containsKey(DevMod.KEY_VERSION)
        && extra.getFilter().containsKey(DevMod.KEY_ARCH);
  }

  protected boolean checkFilter(Map<String, String> devMap, Map<String, String> filterMap) {
    return !devMap.entrySet().containsAll(filterMap.entrySet());
  }

  protected void onStatusCb(ServiceInfoModuleState state, FdoSysModuleExtra extra,
      StatusCb status) throws IOException {
    logger.info("status_cb completed " + status.isCompleted() + " retcode "
        + status.getRetCode() + " timeout " + status.getTimeout());
  }

  protected void onFetch(ServiceInfoModuleState state, FdoSysModuleExtra extra,
      byte[] data) throws IOException {
    String workflowfilepath = "/home/fdo/app-data/workflow.yaml";
    String harwareworkflowpath = "/home/fdo/app-data/hardware.yaml";
    String templateworkflowpath = "/home/fdo/app-data/template.yaml";

    boolean gen = generateWorkflow(data,workflowfilepath);
    boolean genw = generateWorkflow(data,harwareworkflowpath);
    logger.warn(new String(data, StandardCharsets.US_ASCII));
    logger.info("acessing cluster....");
    String kubeConfigPath = "/home/fdo/app-data/config";
    try {
      // loading the out-of-cluster config, a kubeconfig from file-system
      ApiClient client = ClientBuilder.kubeconfig(
              KubeConfig.loadKubeConfig(new FileReader(kubeConfigPath))).build();
      //ApiClient client = Config.defaultClient();
      // set the global default api-client to the in-cluster one from above
      logger.info("loaded kubeconfig....");
      Configuration.setDefaultApiClient(client);
      //creating template resource
      CustomObjectsApi apit = new CustomObjectsApi(client);
      FileInputStream fileInputStreamt = new FileInputStream(templateworkflowpath);
      Yaml yamlt = new Yaml();
      Map<String, Object> cfgt = yamlt.load(fileInputStreamt);
      logger.info("starting template.....................................worflow");
      logger.info(cfgt);
      apit.createNamespacedCustomObject("tinkerbell.org","v1alpha1",
          "tink-system","templates",cfgt,null,null,null);
      //creating hardware resource
      logger.info("created template.....................................starting hardware worflow");
      CustomObjectsApi api = new CustomObjectsApi(client);
      FileInputStream fileInputStream = new FileInputStream(harwareworkflowpath);
      Yaml yaml = new Yaml();
      Map<String, Object> cfg = yaml.load(fileInputStream);
      logger.info(cfg);
      api.createNamespacedCustomObject("tinkerbell.org","v1alpha1",
          "tink-system","hardware",cfg,null,null,null);
      logger.info("create workflow");
      logger.info("created hardware workflow.....................................starting worflow");
      Thread.sleep(30000);   
      CustomObjectsApi api2 = new CustomObjectsApi(client);
      FileInputStream fileInputStream2 = new FileInputStream(workflowfilepath);
      Yaml yamlw = new Yaml();
      Map<String, Object> cfgw = yamlw.load(fileInputStream2);
      logger.info(cfgw);
      api2.createNamespacedCustomObject("tinkerbell.org","v1alpha1",
          "tink-system","workflows",cfgw,null,null,null);
      // Print the map object
    } catch (IOException e) {
      e.printStackTrace();
    } catch (ApiException e) {
      System.err.println("Exception when calling CoreV1Api#listPodForAllNamespaces");
      System.err.println("Status code: " + e.getCode());
      System.err.println("Reason: " + e.getResponseBody());
      System.err.println("Response headers: " + e.getResponseHeaders());
      e.printStackTrace();
      e.printStackTrace();
    } catch (InterruptedException ex) {
      ex.printStackTrace();
    }
    // logger.warn(new String(data, StandardCharsets.US_ASCII));
    logger.warn(new String(data, StandardCharsets.US_ASCII));
  }

  protected boolean  generateWorkflow(byte[] inputData,String workflowfilepath) {
    // Extract device MAC ID from input data (assuming it follows a specific format)
    String deviceMacId = extractDeviceMacId(inputData);
    logger.info("generateWorkflow");
    try {
      // Read the existing content from the workflow file
      String existingContent = Files.readString(Path.of(workflowfilepath));
      // Replace the device MAC ID and template ID in the existing content
      logger.info("generateWorkflow2" + existingContent);
      logger.info("generateWorkflow2deviceMacId" + deviceMacId);
      String updatedContent = updateDeviceMacIdAndTemplateId(existingContent, deviceMacId);
      // Write the updated content back to the workflow file
      Files.write(Path.of(workflowfilepath), updatedContent.getBytes(), 
                    StandardOpenOption.TRUNCATE_EXISTING);
      logger.info("generateWorkflow3");
      return true;
    } catch (IOException e) {
      e.printStackTrace();
      return false; 
    }
  }

  protected String extractDeviceMacId(byte[] inputData) {
    // Assuming the MAC ID is present in the inputData as "macid:aabbccddeeff"
    String inputString = new String(inputData); // Convert byte[] to string
    String macIdPrefix = "macid:";
    int macIdIndex = inputString.indexOf(macIdPrefix);
    logger.info("inputString" + inputString);
    logger.info("macindex" + macIdIndex);
    String deviceMacId; 
    if (macIdIndex != -1) {
      // Extract the MAC ID by removing the prefix
      int macIdEndIndex = inputString.indexOf("\n", macIdIndex);
      deviceMacId = inputString.substring(macIdIndex + macIdPrefix.length(),macIdEndIndex);
      // deviceMacId = inputString.substring(macIdIndex + macIdPrefix.length()).trim();
      logger.info("extractDeviceMacId" + deviceMacId);
      // Remove any leading/trailing whitespace and convert to lowercase
      deviceMacId = deviceMacId.trim().toLowerCase();
      return deviceMacId;
    } else {
      // MAC ID not found using default AA:BB:CC:DD:EE:FF
      deviceMacId = "$TINKERBELL_CLIENT_MAC";
      return deviceMacId;
    }
  }

  protected String updateDeviceMacIdAndTemplateId(String content, String deviceMacId) {
    // Replace the device MAC ID and template ID in the content
    try {
      // String updatedContent = content.replace("$TINKERBELL_CLIENT_MAC", deviceMacId)
      // .replace("templateRef: ubuntu-focal", 
      // "templateRef: new-template-id");
      String updatedContent = content.replace("$TINKERBELL_CLIENT_MAC", deviceMacId.trim());
      logger.info("updatedContent" + updatedContent);
      return updatedContent;
    } catch (Exception e) {
      logger.error("Error occurred while updating device MAC ID and template ID: " 
                  + e.getMessage());
      return null; // Return null or handle the error appropriately in your use case
    } 
  }

  protected  Map<String, Object> parseYamlFile(String filePath) {
    try {
      FileInputStream inputStream = new FileInputStream(filePath);
      Yaml yaml = new Yaml();
      return yaml.load(inputStream);
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    }
    return null;
  }
  
  protected void onEot(ServiceInfoModuleState state, FdoSysModuleExtra extra, EotResult result)
      throws IOException {
    logger.info("EOT:resultCode " + result.getResult());
  }

  protected void load(ServiceInfoModuleState state, FdoSysModuleExtra extra)
      throws IOException {

    if (!state.isActive()) {
      return;
    }

    final Session session = HibernateUtil.getSessionFactory().openSession();
    try {
      Transaction trans = session.beginTransaction();
      SystemPackage systemPackage =
          session.find(SystemPackage.class, Long.valueOf(1));

      if (systemPackage != null) {
        String body = systemPackage.getData().getSubString(1,
            Long.valueOf(systemPackage.getData().length()).intValue());
        FdoSysInstruction[] instructions =
            Mapper.INSTANCE.readJsonValue(body, FdoSysInstruction[].class);

        boolean skip = false;
        for (FdoSysInstruction instruction : instructions) {
          if (instruction.getFilter() != null) {
            skip = checkFilter(extra.getFilter(), instruction.getFilter());
          }
          if (skip) {
            continue;
          }

          if (instruction.getFileDesc() != null) {
            getFile(state, extra, instruction);
          } else if (instruction.getExecArgs() != null) {
            getExec(state, extra, instruction);
          } else if (instruction.getExecCbArgs() != null) {
            getExecCb(state, extra, instruction);
          } else if (instruction.getFetch() != null) {
            getFetch(state, extra, instruction);
          }
        }

      }
      trans.commit();
    } catch (SQLException e) {
      logger.error("SQL Exception" + e.getMessage());
      throw new InternalServerErrorException(e);
    } finally {
      session.close();
    }
  }

  protected void getExec(ServiceInfoModuleState state,
      FdoSysModuleExtra extra,
      FdoSysInstruction instruction) throws IOException {
    ServiceInfoKeyValuePair kv = new ServiceInfoKeyValuePair();
    kv.setKeyName(FdoSys.EXEC);
    kv.setValue(Mapper.INSTANCE.writeValue(instruction.getExecArgs()));
    extra.getQueue().add(kv);
  }

  protected void getExecCb(ServiceInfoModuleState state,
      FdoSysModuleExtra extra,
      FdoSysInstruction instruction) throws IOException {
    ServiceInfoKeyValuePair kv = new ServiceInfoKeyValuePair();
    kv.setKeyName(FdoSys.EXEC_CB);
    kv.setValue(Mapper.INSTANCE.writeValue(instruction.getExecCbArgs()));
    extra.getQueue().add(kv);
  }

  protected void getFetch(ServiceInfoModuleState state,
      FdoSysModuleExtra extra,
      FdoSysInstruction instruction) throws IOException {
    ServiceInfoKeyValuePair kv = new ServiceInfoKeyValuePair();
    kv.setKeyName(FdoSys.FETCH);
    kv.setValue(Mapper.INSTANCE.writeValue(instruction.getFetch()));
    extra.getQueue().add(kv);
  }

  protected void getDbFile(ServiceInfoModuleState state,
      FdoSysModuleExtra extra,
      FdoSysInstruction instruction) throws IOException {
    String resource = instruction.getResource();
    final Session session = HibernateUtil.getSessionFactory().openSession();
    try {
      Transaction trans = session.beginTransaction();
      resource = resource.replace("$(guid)", state.getGuid().toString());

      // Query database table SYSTEM_RESOURCE for filename Key
      SystemResource sviResource = session.get(SystemResource.class, resource);

      if (sviResource != null) {
        Blob blobData = sviResource.getData();
        try (InputStream input = blobData.getBinaryStream()) {
          for (; ; ) {
            byte[] data = new byte[state.getMtu() - 26];
            int br = input.read(data);
            if (br == -1) {
              break;
            }
            ServiceInfoKeyValuePair kv = new ServiceInfoKeyValuePair();
            kv.setKeyName(FdoSys.WRITE);

            if (br < data.length) {
              byte[] temp = data;
              data = new byte[br];
              System.arraycopy(temp, 0, data, 0, br);
            }
            kv.setValue(Mapper.INSTANCE.writeValue(data));
            extra.getQueue().add(kv);
          }
        } catch (SQLException throwables) {
          logger.error("SQL Exception " + throwables.getMessage());
          throw new InternalServerErrorException(throwables);
        }
      } else {
        logger.error("SVI resource missing");
        throw new InternalServerErrorException("svi resource missing " + resource);
      }
      trans.commit();

    } finally {
      session.close();
    }

  }

  protected void getUrlFile(ServiceInfoModuleState state,
      FdoSysModuleExtra extra,
      FdoSysInstruction instruction) throws IOException {
    String resource = instruction.getResource();
    resource = resource.replace("$(guid)", state.getGuid().toString());

    try (CloseableHttpClient httpClient = Config.getWorker(ServiceInfoHttpClientSupplier.class)
        .get()) {

      logger.info("HTTP(S) GET: " + resource);
      HttpGet httpRequest = new HttpGet(resource);
      try (CloseableHttpResponse httpResponse = httpClient.execute(httpRequest)) {
        logger.info(httpResponse.getStatusLine().toString());
        if (httpResponse.getStatusLine().getStatusCode() != 200) {
          throw new InternalServerErrorException(httpResponse.getStatusLine().toString());
        }
        HttpEntity entity = httpResponse.getEntity();
        if (entity != null) {
          logger.info("content length is " + entity.getContentLength());

          try (InputStream input = entity.getContent()) {
            logger.info("reading data");
            for (; ; ) {
              byte[] data = new byte[state.getMtu() - 26];
              int br = input.read(data);
              if (br == -1) {
                break;
              }
              ServiceInfoKeyValuePair kv = new ServiceInfoKeyValuePair();
              kv.setKeyName(FdoSys.WRITE);

              if (br < data.length) {
                byte[] temp = data;
                data = new byte[br];
                System.arraycopy(temp, 0, data, 0, br);
              }
              kv.setValue(Mapper.INSTANCE.writeValue(data));
              extra.getQueue().add(kv);
            }
          }
        }
      }
    } catch (RuntimeException e) {
      logger.error("Runtime Exception" +  e.getMessage());
      throw new InternalServerErrorException(e);
    } catch (Exception e) {
      logger.error("failed to get http content" + e.getMessage());
      throw new InternalServerErrorException(e);
    }
    logger.info("http content downloaded successfully!");

  }

  protected void getFile(ServiceInfoModuleState state,
      FdoSysModuleExtra extra,
      FdoSysInstruction instruction)
      throws IOException {

    ServiceInfoKeyValuePair kv = new ServiceInfoKeyValuePair();
    kv.setKeyName(FdoSys.FILEDESC);
    kv.setValue(Mapper.INSTANCE.writeValue(instruction.getFileDesc()));
    extra.getQueue().add(kv);

    String resource = instruction.getResource();
    if (resource.startsWith("https://") || resource.startsWith("http://")) {
      getUrlFile(state, extra, instruction);
    } else {
      getDbFile(state, extra, instruction);
    }
  }
}
