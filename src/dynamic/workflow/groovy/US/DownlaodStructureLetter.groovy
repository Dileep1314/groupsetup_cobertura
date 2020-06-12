package groovy.US
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.http.HttpStatus

import com.metlife.domain.model.EntityResult
import com.metlife.domain.model.WorkflowDomain
import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory
import com.metlife.gssp.taskflow.Task

/**
 * This API used to download structure letter in excel format
 * @author Naresh/Vishal
 *
 */
class DownlaodStructureLetter implements Task {
	Logger logger = LoggerFactory.getLogger(DownlaodStructureLetter.class)

	@Override
	public Object execute(WorkflowDomain workFlow) {

		/*String workingDir = System.getProperty("user.dir");
		String fileName="StructureLetterTransition.xlsx"
		File file =new File(workingDir +"/src/templates/"+fileName)*/
		def templatePath= workFlow.getEnvPropertyFromContext('templatePath')
		String fileName="StructureLetterTransition.xlsx" 
        File file =new File(templatePath+fileName)
		FileInputStream fis=new FileInputStream(file)
		XSSFWorkbook wb=null
		try {
			wb = new XSSFWorkbook(fis);
			//Workbook wb=WorkbookFactory.create(fis)
		}catch(IOException ex) {
			logger.error("Error While creating workbook "+ex.getMessage())
		}

		Sheet sheet = wb.getSheetAt(0)
		if(sheet.getLastRowNum()>5) {
			for (int i = sheet.getLastRowNum(); i > 5; i--) {
				sheet.removeRow(sheet.getRow(i));
			}
		}
		def requestBody = workFlow.getRequestBody()
		def requestBodyPayload=requestBody['structureLtterDetails']
		logger.info("structureLtterDetails request Body "+requestBodyPayload)
		def structureLtterPayload = requestBodyPayload[0]
		def newStructure=getStructure(structureLtterPayload)
		logger.info("structureLtterDetails newStructure"+newStructure)
		def requestBodyNew=formateStructure(newStructure)
		logger.info("structureLtterDetails requestBodyNew"+requestBodyNew)
		def groupNumber=structureLtterPayload?.groupNumber
		def groupName=structureLtterPayload?.groupName
		def structureEffectiveDate=structureLtterPayload?.structureEffectiveDate
		def coverageDetails=requestBodyNew?.coverageDetails
		def inputArray=createInputData(requestBodyNew,groupNumber)
		logger.info("structureLtterDetails requestBodyNew"+inputArray)
		Arrays.sort(inputArray, new Comparator<String[]>(){
			@Override
			public int compare(String[] col1, String[] col2){
				final String input1 = col1[7];
				final String input2 = col2[7];
				int compare = input1.compareTo(input2);
				if(compare != 0){
					return compare;
				}else {
					return col1[0].compareTo(col2[0]);
				}
			}

		})
		def output=writeDataTOExcel(groupName,structureEffectiveDate,inputArray,groupNumber,file,wb,sheet,coverageDetails)
		logger.info("structureLtterDetails output"+output)
		def response=convertToBase64Format(file)
		logger.info("structureLtterDetails response"+response)
		def downloadRespons=[:] as Map
		downloadRespons.putAt("Details", response)
		downloadRespons.putAt("formatCode", "excel")
		downloadRespons.putAt("name", "StructureLetterHistory.xlsx")
		workFlow.addResponseBody(new EntityResult(downloadRespons, true))
		workFlow.addResponseStatus(HttpStatus.OK)
	}
	/**
	 * @param newStructure
	 * @return
	 */
	def formateStructure(newStructure){
		ArrayList newList=new ArrayList()
		newStructure.each {
			outputIndex->
			outputIndex.each {
				key,value->
				value.each {
					valueInput->
					newList.add(valueInput)
				}
			}
		}
		HashMap requestBody=new HashMap()
		requestBody.put("coverageDetails",newList)
		logger.info("Structure Data for Excel download ===>"+ requestBody)
		requestBody
	}

	/**
	 * This Method is used to form the structure of excel row
	 * @param structureLtterDetails
	 * @return
	 */
	def getStructure(structureLtterDetails) {
		def groupNumber=structureLtterDetails?.groupNumber
		def groupStructure=structureLtterDetails?.groupStructure
		def subGroup=groupStructure?.subGroup
		def classDefinition=groupStructure?.classDefinition
		def dhmoState=structureLtterDetails?.dhmoStates
		def response
		Map classDefMap
		ArrayList rowList=new ArrayList()
		subGroup.each{
			subgroupInput ->
			def subGroupNumber=subgroupInput?.subGroupNumber
			def assignClassLocation=subgroupInput?.assignClassLocation
			def buildCase=subgroupInput?.buildCaseStructure
			def departments=buildCase?.departments
			assignClassLocation.each {
				assignClassIDInput ->
				def classId=assignClassIDInput?.classId
				if(departments) {
					departments.each{
						departmentsInput ->
						def departmentCode=departmentsInput.departmentCode
						def departmentDescription=departmentsInput.departmentDescription

						formateStrucure(groupNumber,dhmoState,rowList,assignClassIDInput,classDefinition,classDefMap,subGroupNumber,buildCase,departmentCode,classId)
					}
				}else {
					def departmentCode=""
					formateStrucure(groupNumber,dhmoState,rowList,assignClassIDInput,classDefinition,classDefMap,subGroupNumber,buildCase,departmentCode,classId)
				}
			}
		}
		rowList
	}

	def formateStrucure(groupNumber,dhmoState,List rowList,assignClassIDInput,classDefinition,classDefMap,subGroupNumber,buildCase,departmentCode,classId) {

		Map classDefClassId=new HashMap()
		//Uncomment the block for duplicate issue
		/*if(classDefClassId !=null  && classDefClassId.containsKey(classId)) {
		 //println "same key "+classId
		 }*/
		classDefinition.each { classDefInput->
			def existingClassName=classDefInput?.existingClassName
			if(classId==existingClassName || classId.equals(existingClassName)) {
				def existingClassId=classDefInput.existingClassId
				def existingClassDescription=classDefInput.existingClassDescription
				def productDetails=classDefInput.productDetails
				def censusFileName=classDefInput?.censusFileName
				HashSet productSet=new HashSet()
				productSet=classDefInput.products
				def planChoice=classDefInput?.planChoice?.choice
				ArrayList excelRowList=new ArrayList();
				productDetails.each { productsInput->
					
					def coverageTypeCT=productsInput?.coverageTypeCT
					if(!coverageTypeCT.equals("*")) {
						productSet.add(coverageTypeCT)
					}
				}
				productSet.each { productInput->
					classDefMap=new HashMap()
					classDefMap.put("groupNumber", groupNumber)
					if(productInput.equals("DHMO")) {
						classDefMap.put("dhmoStateCode", dhmoState)
					}else {
						classDefMap.put("dhmoStateCode", "")
					}
					classDefMap.put("classNumber", existingClassId)
					classDefMap.put("planCode", productInput)
					classDefMap.put("classDescription", existingClassDescription)
					/*def censusClassName
					String existingClass=existingClassDescription
					if(!existingClass.isEmpty()) {
					int firstIndex=existingClass.lastIndexOf("Time");
					int lastIndex=existingClass.length();
					 censusClassName=(String) existingClass.subSequence(firstIndex+4 , lastIndex);
					}*/
					classDefMap.put("location", subGroupNumber)
					classDefMap.put("departmentCode", departmentCode)
					classDefMap.put("censusClass", censusFileName)
					productDetails.each{ productDetailsInput ->
						def provisionName=productDetailsInput?.provisionName
						def provisionValue=productDetailsInput?.provisionValue
						def productsProvision=productDetailsInput?.products
						def coverageTypeCT=productDetailsInput?.coverageTypeCT
						def choices=productDetailsInput?.choices
						if(productsProvision.contains(productInput)) {
							classDefMap.put("planChoice", choices)
							if(productInput.equals("DHMO") || productInput.equals("DPPO") || productInput.equals("VIS") || productInput.equals("ACC") || productInput.equals("CIAA") || productInput.equals("HI")){
								if(provisionName.equals("PRESENTEMPLOYEEWAITINGPERIOD")) {
									classDefMap.put("waitingPeriod", provisionValue)
								}
								if(provisionName.equals("FUNDINGSHARINGAMOUNT" )) {
									classDefMap.put("employerContributionDetails",provisionValue)
								}
							}else if( productInput.equals("BSCL")) {

									if(provisionName.equals("PRESENTEMPLOYEEWAITINGPERIOD")) {
										classDefMap.put("waitingPeriod", provisionValue)
									}
									if(provisionName.equals("FUNDINGSHARINGAMOUNT" )) {
										classDefMap.put("employerContributionDetails",provisionValue)
									}
									if(provisionName.equals("COVERAGEBASIS")) {
										classDefMap.put("coverageBenefitAmount",provisionValue)
									}
									if(provisionName.equals("MAXBENEFITAMOUNT")  || provisionName.equals("COVERAGEAMOUNT")) {
										classDefMap.put("coverageMaximum",provisionValue)
									}
								}
							   else if(productInput.equals("PADD") || productInput.equals("BDEPLS") || productInput.equals("BDEPLC")) {

									if(provisionName.equals("PRESENTEMPLOYEEWAITINGPERIOD")) {
										classDefMap.put("waitingPeriod", provisionValue)
									}
									if(provisionName.equals("FUNDINGSHARINGAMOUNT" )) {
										classDefMap.put("employerContributionDetails",provisionValue)
									}
									if(provisionName.equals("COVERAGEBASIS")) {
										classDefMap.put("coverageBenefitAmount",provisionValue)
									}
									if(provisionName.equals("MAXBENEFITAMOUNT") || provisionName.equals("COVERAGEAMOUNT")) {
										classDefMap.put("coverageMaximum",provisionValue)
									}
								}else if( productInput.equals("OPTL")){
									if(provisionName.equals("PRESENTEMPLOYEEWAITINGPERIOD")) {
										classDefMap.put("waitingPeriod", provisionValue)
									}
									if(provisionName.equals("FUNDINGSHARINGAMOUNT" )) {
										classDefMap.put("employerContributionDetails",provisionValue)
									}
									if(provisionName.equals("INCREMENTAMOUNT")) {
										classDefMap.put("coverageBenefitAmount",provisionValue)
									}
									if(provisionName.equals("MAXBENEFITAMOUNT")) {
										classDefMap.put("coverageMaximum",provisionValue)
									}
								}
								if(productInput.equals("OPTADD") || productInput.equals("DEPLS") || productInput.equals("DADDS") || productInput.equals("DEPLC") || productInput.equals("DADDC")) {
									if(provisionName.equals("PRESENTEMPLOYEEWAITINGPERIOD")) {
										classDefMap.put("waitingPeriod", provisionValue)
									}
									if(provisionName.equals("FUNDINGSHARINGAMOUNT" )) {
										classDefMap.put("employerContributionDetails",provisionValue)
									}
									if(provisionName.equals("INCREMENTAMOUNT")) {
										classDefMap.put("coverageBenefitAmount",provisionValue)
									}
									if(provisionName.equals("MAXBENEFITAMOUNT")) {
										classDefMap.put("coverageMaximum",provisionValue)
									}
								}

							else if(productInput.equals("LTD") || productInput.equals("STD")) {
								if(provisionName.equals("PRESENTEMPLOYEEWAITINGPERIOD")) {
									classDefMap.put("waitingPeriod", provisionValue)
								}
								if(provisionName.equals("FUNDINGSHARINGAMOUNT" )) {
									classDefMap.put("employerContributionDetails",provisionValue)
								}
								if(provisionName.equals("BENEFITAMOUNT")) {
									classDefMap.put("coverageBenefitAmount",provisionValue)
								}
								if(provisionName.equals("MAXBENEFITAMOUNT")) {
									classDefMap.put("coverageMaximum",provisionValue)
								}
							}
						}
					}
					if(productInput.equals("DHMO") || productInput.equals("DPPO") || productInput.equals("VIS") || productInput.equals("ACC") || productInput.equals("CIAA") || productInput.equals("HI")){
						switch(productInput) {
							case "DHMO" :
							classDefMap.put("planCodeDescription", "Dental Managed Care Plan")
							break
							case "DPPO" :
							classDefMap.put("planCodeDescription", "Dental Insurance")
							break
							case "CIAA" :
							classDefMap.put("planCodeDescription", "Critical Illness Insurance")
							break
							case "ACC" :
							classDefMap.put("planCodeDescription", "Accident Insurance")
							break
							case "VIS" :
							classDefMap.put("planCodeDescription", "Vision Insurance")
							break
							case "HI" :
							classDefMap.put("planCodeDescription", "Hospital Indemnity Insurance")
							break
						}
						classDefMap.put("coverageBenefitAmount","")
						classDefMap.put("coverageMaximum","")
					}else if(productInput.equals("LTD") || productInput.equals("STD") || productInput.equals("BSCL") || productInput.equals("OPTL") || productInput.equals("OPTADD") || productInput.equals("DEPLS") || productInput.equals("DADDS") || productInput.equals("DEPLC") || productInput.equals("DADDC") || productInput.equals("PADD") || productInput.equals("BDEPLS") || productInput.equals("BDEPLC")) {
						
						switch(productInput) {
							case "LTD" :
							classDefMap.put("planCodeDescription", "Long Term Disability Insurance")
							break
							case "STD" :
							classDefMap.put("planCodeDescription", "Short Term Disability Insurance")
							break
							case "BSCL" :
							classDefMap.put("planCodeDescription", "Basic Life")
							break
							case "PADD" :
							classDefMap.put("planCodeDescription", "Basic AD&D")
							break
							case "BDEPLS" :
							classDefMap.put("planCodeDescription", "Basic Dependent Spouse Life")
							break
							case "BDEPLC" :
							classDefMap.put("planCodeDescription", "Basic Dependent Child Life")
							break
							case "OPTL" :
							classDefMap.put("planCodeDescription", "Supplemental Life")
							break
							case "OPTADD" :
							classDefMap.put("planCodeDescription", "Supplemental AD&D")
							break
							case "DEPLS" :
							classDefMap.put("planCodeDescription", "Supplemental Dependent Spouse Life")
							break
							case "DADDS" :
							classDefMap.put("planCodeDescription", "Supplemental Dependent Spouse AD&D")
							break
							case "DEPLC" :
							classDefMap.put("planCodeDescription", "Supplemental Dependent Child Life")
							break
							case "DADDC" :
							classDefMap.put("planCodeDescription", "Supplemental Dependent Child AD&D")
							break
						}
					}
					else if(productInput.equals("LGL")) {
						classDefMap.put("planCodeDescription", "Met Law Group Legal Plan")

					}
					excelRowList.add(classDefMap)
				}
				classDefClassId.put(existingClassName, excelRowList)
				rowList.add(classDefClassId)
			}
		}

	}
	/**
	 * This Method used for creation of 2-d matrix based on data
	 * @param requestBody
	 * @param customerNumber
	 * @param censusClass
	 * @return
	 */
	def createInputData(def requestBody,def customerNumber) {
		def coverageDetails=requestBody?.coverageDetails
		Object[][] bookData = new String[coverageDetails.size()][coverageDetails.size()];
		int count=0;
		for(def planDetails:coverageDetails) {
			def coveragePlanCode=planDetails?.planCode
			def coveragePlanCodeDescription=planDetails?.planCodeDescription
			def coveragePlanChoice=planDetails?.planChoice
			def location=planDetails?.location
			def coverageBenefitAmount=planDetails?.coverageBenefitAmount
			def coverageMaximum=planDetails?.coverageMaximum
			def classNumber=planDetails?.classNumber
			def classDescription=planDetails?.classDescription
			def departmentCode=planDetails?.departmentCode
			def waitingPeriod=planDetails?.waitingPeriod
			def censusClass=planDetails?.censusClass
			def employerContributionDetails=planDetails?.employerContributionDetails
			def dhmoStateCode=planDetails?.dhmoStateCode
			Object[] objectarray=new String[14]
			objectarray[0]=coveragePlanCode
			objectarray[1]=coveragePlanCodeDescription
			objectarray[2]=coveragePlanChoice
			objectarray[3]=coverageBenefitAmount
			objectarray[4]=coverageMaximum
			objectarray[5]=customerNumber
			objectarray[6]=location
			objectarray[7]=classNumber
			objectarray[8]=classDescription
			objectarray[9]=censusClass
			objectarray[10]=departmentCode
			objectarray[11]=waitingPeriod
			objectarray[12]=employerContributionDetails
			objectarray[13]=dhmoStateCode
			bookData[count]=objectarray
			count++;
		}
		bookData
	}

	/**
	 * This Method is basically writing data into excel using user provide data
	 * @param groupName
	 * @param structureEffectiveDate
	 * @param bookData
	 * @param customerNumber
	 * @param file
	 * @param wb
	 * @param sheet
	 * @param coverageDetails
	 * @return
	 */
	def writeDataTOExcel(groupName,structureEffectiveDate,Object[][] bookData,def customerNumber,File file,Workbook workBook,Sheet sheet,def coverageDetails) {

		def strArray1 = ["STRUCTURE TEMPLATE", "For", groupName, "Structure Effective Date: "+structureEffectiveDate] as String[]
		def strarray2 = ["Customer Number: "+customerNumber] as String[]
		FileOutputStream fos=null
		Integer size=coverageDetails.size()
		try{
			fos=new FileOutputStream(file)

			createDynamicdata(strArray1,sheet.getRow(0).getCell(0),workBook)
			createDynamicdata(strarray2,sheet.getRow(2).getCell(0),workBook)
			int rowCount = 5
			CellStyle style = workBook.createCellStyle()
			for (Object[] aBook : bookData) {
				Row row = sheet.createRow(rowCount++)

				int columnCount = 0
				for (Object field : aBook) {
					Cell cell = row.createCell(columnCount++)
					cell.setCellValue(field)
					style.setBorderBottom(CellStyle.BORDER_THIN)
					style.setBorderLeft(CellStyle.BORDER_THIN)
					style.setBorderRight(CellStyle.BORDER_THIN)
					style.setBorderTop(CellStyle.BORDER_THIN)
					cell.setCellStyle(style)
				}
			}
			workBook.write(fos)
		}
		catch(Exception ex) {
			logger.error("Error while writting into excel template "+ex.getMessage())
			ex.printStackTrace(ex.getMessage())
		}
		finally {
			fos.close()
		}

		workBook
	}
	/**
	 * @param strArray
	 * @param cell
	 * @param wb
	 * @return
	 */
	def createDynamicdata(String[] strArray,Cell cell,Workbook wb) {
		CellStyle borderStyle = wb.createCellStyle()
		StringBuilder sb =new StringBuilder()
		for(def str:strArray) {
			sb.append(str != null ? str : "").append(System.getProperty("line.separator"))
		}
		cell.setCellValue(sb.toString())
		borderStyle.setAlignment(CellStyle.ALIGN_CENTER)
	}
	/**
	 * To convert the excel response in base64 format
	 * @param file
	 * @return
	 */
	def convertToBase64Format(File file) {
		String base64Response=null
		FileInputStream fis=null
		try {
			byte[] bytes = new byte[(int) file.length()]
			fis = new FileInputStream(file)
			fis.read(bytes)
			base64Response = new sun.misc.BASE64Encoder().encode(bytes)
		}
		catch(IOException ex) {
			logger.error("Error While converting excel into base64 "+ex.getMessage())
		}
		finally {

			fis.close()
		}
		base64Response
	}
}
