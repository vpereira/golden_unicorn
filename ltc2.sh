#!/bin/bash
javac -cp ".:./ztex/libusbJava:./ztex/java:" LTCMiner2.java
jar cf LTCMiner2.jar *.class ztex_ufm1_15b1.ihx ztex_ufm1_15d4.ihx ztex_ufm1_15y1.ihx ztex_ufm1_15d.ihx ztex_ufm1_15y.ihx ztex_ufm1_15d4-nomac.ihx ztex_ufm1_15y1-nomac.ihx fpga/ztex_ufm1_15b1.bit fpga/ztex_ufm1_15d1.bit fpga/ztex_ufm1_15d3.bit fpga/ztex_ufm1_15d4.bit fpga/ztex_ufm1_15y1.bit  -C ./ztex/libusbJava . -C ./ztex/java ztex/AlreadyConfiguredException.class -C ./ztex/java ztex/BitstreamReadException.class -C ./ztex/java ztex/BitstreamUploadException.class -C ./ztex/java ztex/CapabilityException.class -C ./ztex/java ztex/DeviceLostException.class -C ./ztex/java ztex/DeviceNotSupportedException.class -C ./ztex/java ztex/EzUsb.class -C ./ztex/java ztex/FirmwareUploadException.class -C ./ztex/java ztex/IhxFile.class -C ./ztex/java ztex/IhxFileDamagedException.class -C ./ztex/java ztex/IhxParseException.class -C ./ztex/java ztex/IncompatibleFirmwareException.class -C ./ztex/java ztex/InvalidFirmwareException.class -C ./ztex/java ztex/JInputStream.class -C ./ztex/java ztex/UsbException.class -C ./ztex/java ztex/Ztex1.class -C ./ztex/java ztex/Ztex1v1.class -C ./ztex/java ztex/ZtexDevice1.class -C ./ztex/java ztex/ZtexIhxFile1.class -C ./ztex/java ztex/ZtexScanBus1.class
