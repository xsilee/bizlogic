package com.biddingo.common.formitem.service;

@Service("CFormBusinessLogicService")
public class CFormBusinessLogicServiceImpl implements CFormBusinessLogicService {
	
	private static final Logger logger = LoggerFactory.getLogger(CFormBusinessLogicServiceImpl.class);
	
	@Override
	@Transactional
	public void processEvaluationByPrice(User user, 
										 Integer procurementId, 
										 Integer cformContentId, 
										 Integer itemId,
										 String applicationType) throws Exception {
		
		Integer sysId = user.getSysId();
		Integer orgId = user.getOrgId();
		
		Tender tender = tenderDAO.findTenderByPrimaryKey(sysId, orgId, procurementId);

		// Get Content List from Item Bidder table
		Set<BigDecimal> contentIdList = wbsItemBidderService.getContentIdsByProcuremetIdAndItemId(sysId, orgId, procurementId, itemId); // contents_ids per wbsFormItemId
		List<String> responseIdsList = new ArrayList<String>();
		if (itemId > 0) {
			CItem citem = cItemService.getCItemByPrimaryKey(sysId, orgId, cformContentId, itemId);		
			responseIdsList = new ArrayList<String>(Arrays.asList(StringUtils.split(citem.getResponseFormIds() == null ? "" : citem.getResponseFormIds(),",")));
			responseIdsList.stream().forEach(conId -> contentIdList.add(new BigDecimal(conId)));
		}
		
		//-----------------------------------------
		// Get all qualified bidder list
		//-----------------------------------------
		Map<Integer, List<TenderBidder>> finalSelectedBidderMap = new HashMap<Integer, List<TenderBidder>>(); // selected bidder per contents_id
		Map<Integer, TenderBidder> selectedBidderMap = new HashMap<Integer, TenderBidder>(); // bidders in procurement
		Map<Integer, TenderBidder> unSelectedBidderMap= new HashMap<Integer, TenderBidder>(); // bidders for Non-compliant 
		
		//-----------------------------------------
		// Get all qualified bidder list
		//-----------------------------------------
		Set<TenderBidder> submittedBidderList = tenderBidderDAO.findSubmittedTenderBidder(sysId, orgId, procurementId);
		for (TenderBidder tenderBidder: submittedBidderList) {
			boolean disqualified = false;
			Set<BidAnalysis> bidAnalysisList = bidAnalysisDAO.findBidAnalysisBySupplier(sysId, orgId, procurementId, tenderBidder.getBidderOrgId());
			for (BidAnalysis bidAnalysis : bidAnalysisList) {
				if ("Y".equals(bidAnalysis.getDisqualifiedYn())) {
					disqualified = true;
					break;
				}
			}
			if (disqualified) {
				unSelectedBidderMap.put(tenderBidder.getBidderOrgId(), tenderBidder);
			} else {
				selectedBidderMap.put(tenderBidder.getBidderOrgId(), tenderBidder);  // Put qualified all bidders in procurement_id .{bidderId, TenderBidder}
			}
		}
		
		BidDocAddendum bidDocAddendum = bidDocAddendumDAO.findFinalPublishedBidDocAddendum(sysId, orgId, procurementId);
		
		Map<String, BidDocContentBean> bidderContentBeanMap = new HashMap<String, BidDocContentBean>(); // {contents_id_bidder_id, BidDocContentBean}
		List<BidDocContentBean> priceContentBeanList = new ArrayList<BidDocContentBean>();              // list of contentBean which has price field
		
		Map<Integer, List<TenderBidder>> contentBidderMap = new HashMap<Integer, List<TenderBidder>>();
		for (Map.Entry<Integer, TenderBidder> entry : selectedBidderMap.entrySet()) {
			Set<BigDecimal> contentIds = new HashSet<>();
			if (bidDocAddendum == null) {
				contentIds = bidDocItemResponseService.getContentIdsBySuplId(procurementId, entry.getKey());
				contentIds.addAll(jBidDocItemResponseService.getContentIdsBySuplId(procurementId, entry.getKey()));
			} else {
				contentIds = bidDocAddItemResponseService.getContentIdsBySuplId(procurementId, bidDocAddendum.getAddendumId(), entry.getKey());
				contentIds.addAll(jBidDocItemResponseService.getContentIdsByAddendumIdAndSuplId(procurementId, bidDocAddendum.getAddendumId(), entry.getKey()));
			}
			contentIds.stream().forEach(conId -> {                                                                       
				if (contentBidderMap.containsKey(Integer.valueOf(conId.toString()))) {                                   
					contentBidderMap.get(Integer.valueOf(conId.toString())).add(entry.getValue());
				} else {
					List<TenderBidder> tenderBidderList = new ArrayList<TenderBidder>();
					tenderBidderList.add(entry.getValue());
					contentBidderMap.put(Integer.valueOf(conId.toString()), tenderBidderList);
				}
			});
		}
		
		if (bidDocAddendum == null) {
			if (ApplicationType.WBS.getLabel().equals(applicationType)) {
				Set<BidDocumentsContents> secondPackageBidDocContentList = bidDocumentsContentsDAO.findBidDocumentsContentsByHasPricingItem(sysId, orgId, procurementId, "Y");
				contentIdList.stream().forEach(conId -> {
					for (Map.Entry<Integer, List<TenderBidder>> entry : contentBidderMap.entrySet()) {
						if (Integer.valueOf(conId.toString()).equals(entry.getKey())) {
							secondPackageBidDocContentList.stream()
								.filter(content -> {
									if (String.valueOf(content.getDocContentId()).equals(String.valueOf(entry.getKey()))) {
										return true;
									} else {
										return false;
									}
								}).forEach(content -> {
									AtomicInteger index = new AtomicInteger(0);
									BidDocContentBean priceContentBean = new BidDocContentBean(content, null);
									priceContentBean.setSubAccessId(itemId);
									
									List<TenderBidder> selectedBidderList = new ArrayList<TenderBidder>();
									entry.getValue().stream().forEach(selectedBidder -> {
										BidDocContentBean contentBean = new BidDocContentBean(content, null);
										contentBean.setSubAccessId(itemId);
										try {
											contentBean = tenderAnalysisService.getBidPricingInformationByBidder(user, contentBean, procurementId, tender.getTenderId(), selectedBidder);
											if (index.get() == 0) {
												priceContentBean.setTreeBidDocItemBeanList(contentBean.getTreeBidDocItemBeanList());
												priceContentBeanList.add(priceContentBean);
												index.incrementAndGet();
											}		
											bidderContentBeanMap.put(content.getDocContentId() + "_" + selectedBidder.getBidderOrgId(), contentBean);
											if (!selectedBidderList.contains(selectedBidder)) {
												selectedBidderList.add(selectedBidder);
											}
										} catch (Exception e) {
											e.printStackTrace();
											//throw new RuntimeException("Fail! : processEvaluationByPrice -> " + e.getMessage());
										}
									});
									
									finalSelectedBidderMap.put(content.getDocContentId(), selectedBidderList);
								});
						}
					}
				});
			} else {
				for (Map.Entry<Integer, List<TenderBidder>> entry : contentBidderMap.entrySet()) {
					Set<BidDocumentsContents> secondPackageBidDocContentList = bidDocumentsContentsDAO.findBidDocumentsContentsByHasPricingItem(sysId, orgId, procurementId, "Y");
					for (BidDocumentsContents content: secondPackageBidDocContentList) {
						Map<String, BidDocContentBean> bidderContentMap = new HashMap<String, BidDocContentBean>(); 
						List<BidDocContentBean> priceContentList = new ArrayList<BidDocContentBean>();
						List<TenderBidder> selectedBidderList = new ArrayList<TenderBidder>();
						
						if (String.valueOf(content.getDocContentId()).equals(String.valueOf(entry.getKey()))) {
							int index = 0;
							BidDocContentBean priceContentBean = new BidDocContentBean(content, null);
							for (TenderBidder selectedBidder : entry.getValue()) {
								BidDocContentBean contentBean = new BidDocContentBean(content, null);
								contentBean = tenderAnalysisService.getBidPricingInformationByBidder(user, contentBean, procurementId, tender.getTenderId(), selectedBidder);
								if (index == 0) {
									priceContentBean.setTreeBidDocItemBeanList(contentBean.getTreeBidDocItemBeanList());
									priceContentList.add(priceContentBean);
									index++;
								}					
								bidderContentMap.put(content.getDocContentId() + "_" + selectedBidder.getBidderOrgId(), contentBean);
								selectedBidderList.add(selectedBidder);
							}
							
							finalSelectedBidderMap.put(content.getDocContentId(), selectedBidderList);
							persistEvaluation(user, priceContentList, bidderContentMap, finalSelectedBidderMap, applicationType);
						}
					}
				}
				return;
			}
		} else {
			if (ApplicationType.WBS.getLabel().equals(applicationType)) {
				Set<BidDocAddendumContent> secondPackageBidDocContentList = bidDocAddendumContentDAO.findBidDocAddendumContentByHasPricingItem(sysId, orgId, procurementId, bidDocAddendum.getAddendumId(), "Y");
				contentIdList.stream().forEach(conId -> {
					for (Map.Entry<Integer, List<TenderBidder>> entry : contentBidderMap.entrySet()) {
						if (Integer.valueOf(conId.toString()).equals(entry.getKey())) {
							secondPackageBidDocContentList.stream()
								.filter(content -> {
									if (String.valueOf(content.getContentsId()).equals(String.valueOf(entry.getKey()))) {
										return true;
									} else {
										return false;
									}
								}).forEach(content -> {
									AtomicInteger index = new AtomicInteger(0);
									BidDocContentBean priceContentBean = new BidDocContentBean(content, null);
									priceContentBean.setSubAccessId(itemId);
									
									List<TenderBidder> selectedBidderList = new ArrayList<TenderBidder>();
									entry.getValue().stream().forEach(selectedBidder -> {
										BidDocContentBean contentBean = new BidDocContentBean(content, null);
										contentBean.setSubAccessId(itemId);
										try {
											contentBean = tenderAnalysisService.getBidPricingInformationByBidder(user, contentBean, procurementId, tender.getTenderId(), selectedBidder);
											if (index.get() == 0) {
												priceContentBean.setTreeBidDocItemBeanList(contentBean.getTreeBidDocItemBeanList());
												priceContentBeanList.add(priceContentBean);
												index.incrementAndGet();
											}		
											bidderContentBeanMap.put(content.getContentsId() + "_" + selectedBidder.getBidderOrgId(), contentBean);
											if (!selectedBidderList.contains(selectedBidder)) {
												selectedBidderList.add(selectedBidder);
											}
										} catch (Exception e) {
											e.printStackTrace();
											//throw new RuntimeException("Fail! : processEvaluationByPrice -> " + e.getMessage());
										}
									});
									
									finalSelectedBidderMap.put(content.getContentsId(), selectedBidderList);
								});
						}
					}
				});
			} else {
				for (Map.Entry<Integer, List<TenderBidder>> entry : contentBidderMap.entrySet()) {
					Set<BidDocAddendumContent> secondPackageBidDocContentList = bidDocAddendumContentDAO.findBidDocAddendumContentByHasPricingItem(sysId, orgId, procurementId, bidDocAddendum.getAddendumId(), "Y");
					for (BidDocAddendumContent content: secondPackageBidDocContentList) {
						Map<String, BidDocContentBean> bidderContentMap = new HashMap<String, BidDocContentBean>(); 
						List<BidDocContentBean> priceContentList = new ArrayList<BidDocContentBean>();
						List<TenderBidder> selectedBidderList = new ArrayList<TenderBidder>();
						
						if (String.valueOf(content.getContentsId()).equals(String.valueOf(entry.getKey()))) {
							int index = 0;
							BidDocContentBean priceContentBean = new BidDocContentBean(content, null);
							for (TenderBidder selectedBidder : entry.getValue()) {
								BidDocContentBean contentBean = new BidDocContentBean(content, null);
								contentBean = tenderAnalysisService.getBidPricingInformationByBidder(user, contentBean, procurementId, tender.getTenderId(), selectedBidder);
								if (index == 0) {
									priceContentBean.setTreeBidDocItemBeanList(contentBean.getTreeBidDocItemBeanList());
									priceContentList.add(priceContentBean);
									index++;
								}					
								bidderContentMap.put(content.getContentsId() + "_" + selectedBidder.getBidderOrgId(), contentBean);
								selectedBidderList.add(selectedBidder);
							}
							
							finalSelectedBidderMap.put(content.getContentsId(), selectedBidderList);
							persistEvaluation(user, priceContentList, bidderContentMap, finalSelectedBidderMap, applicationType);
						}
					}
				}
				return;
			}
		}
	
		/*
		 * Persist all entities : Only for Business logic regarding Tender Analysis with pricing package.
		 */
		persistEvaluation(user, priceContentBeanList, bidderContentBeanMap, finalSelectedBidderMap, applicationType);
	}
	
	
	@Override
	public void persistEvaluation(User user, 
							  	  List<BidDocContentBean> priceContentBeanList, 
							  	  Map<String, BidDocContentBean> bidderContentBeanMap, 
							  	  Map<Integer, List<TenderBidder>> selectedBidderListMap,
							  	  String applicationType) throws Exception {
		
		for (BidDocContentBean contentBean : priceContentBeanList) {
			
			List<TenderBidder> selectedBidderList = selectedBidderListMap.get(contentBean.getContentId());
			
			//-----------------------------------
			// Save CForm Entity
			//-----------------------------------
			CForm form = saveFormEntity(user, contentBean, applicationType);
			
			//-----------------------------------
			// Save CField Entities
			//-----------------------------------
			List<CField> newFieldList = new ArrayList<>();
			List<Integer> bidderOrgIdList = new ArrayList<>();
			saveCFieldEntities(user, 
					   		   contentBean, 
					   		   form, 
					   		   selectedBidderList, 
					   		   newFieldList, 
					   		   bidderOrgIdList);
			

			//-----------------------------------
			// Save CItem Entities
			//-----------------------------------
			List<CItem> newitemList = new ArrayList<>();
			List<CItem> parentItemList = new ArrayList<>();
			List<CItem> cloneParentItemList = new ArrayList<>();
			if (contentBean.getTreeBidDocItemBeanList() == null) continue;

			Integer listSeq = 1;
			listSeq = saveCItemEntities(user, 
					   		  			contentBean,
					   		  			newitemList,
					   		  			parentItemList,
					   		  			cloneParentItemList,
					   		  			form,
					   		  			listSeq);
			
			//---------------------------------------------------------------
			// Extract all item data From Json Item table if existing
			//---------------------------------------------------------------
			Map<String, String> responseValueMap 		= new HashMap<String, String>();
			Map<String, String> responseNoteMap 		= new HashMap<String, String>();
			Map<String, String> uomMap 					= new HashMap<String, String>();
			Map<String, String> quantityMap 			= new HashMap<String, String>();
			Map<String, String> orgFormulaExpressionMap	= new HashMap<String, String>();
			Map<String, String> orgFormulaPositionMap	= new HashMap<String, String>();
			Map<String, Integer> itmeIdAndOptionIdMap 	= new HashMap<String, Integer>();
			Map<Integer, Set<CItemAttach>> itemAttachMap = new HashMap<Integer, Set<CItemAttach>>();
			
			if (("Y".equals(contentBean.getIsAlternativeOption()) && !"Y".equals(contentBean.getIsOptionByBuyer())) 
					|| "Y".equals(contentBean.getIsFreeResponseForm())) {

				Map<Integer, Map<Integer, Map<Integer, FxCellData>>> resultFormulaMatrixMap = new HashMap<Integer, Map<Integer, Map<Integer, FxCellData>>>();				
				// Get Options Ids first
				List<BigDecimal> itemOptionList = jBidDocItemResponseService.getItemOptionIdByContentId(contentBean.getProcurementId(), contentBean.getContentId());
				
				// Do process for free style options
				if ("Y".equals(contentBean.getIsFreeResponseForm())) {
					// Show items by Master Codes : Within the same master codes in the same option id, 5 items for A bidder, 3 items for B bidder = total 5 items
					listSeq = saveCItemEntities4FreeOption(user, 
			  				 					 		   contentBean,
			  				 					 		   form,
			  				 					 		   newitemList,
			  				 					 		   parentItemList,
			  				 					 		   cloneParentItemList,			   								  
			  				 					 		   newFieldList,
			  				 					 		   selectedBidderList,
			  				 					 		   responseValueMap,
			  				 					 		   responseNoteMap,
			  				 					 		   uomMap,
			  				 					 		   quantityMap,
			  				 					 		   orgFormulaExpressionMap,
			  				 					 		   orgFormulaPositionMap,
			  				 					 		   itmeIdAndOptionIdMap,
			  				 					 		   itemAttachMap,
			  				 					 		   resultFormulaMatrixMap,
			  				 					 		   itemOptionList,
			  				 					 		   listSeq);
				} else {
					// Show items by Bidders : 5 items for A bidder + 3 itmes for B bidder = total 8 items
					listSeq = saveCItemEntities4AlternativeOption(user, 
								 								  contentBean,
								 								  form,
								 								  newitemList,
								 								  parentItemList,
								 								  cloneParentItemList,								 						
								 								  selectedBidderList,
								 								  responseValueMap,
								 								  responseNoteMap,
								 								  uomMap,
								 								  quantityMap,
								 								  orgFormulaExpressionMap,
								 								  orgFormulaPositionMap,
								 								  resultFormulaMatrixMap,	
								 								  itemAttachMap,
								 								  itemOptionList,
								 								  itmeIdAndOptionIdMap,
								 								  listSeq);
				}
			}

			newitemList.removeAll(cloneParentItemList);
			newitemList = (List<CItem>) PersistenceUtil.saveNewEntityList(entityManager, newitemList);
			newitemList.addAll(cloneParentItemList);
			adjustItemTreeHierarchy(newitemList);
			
			//-----------------------------------
			// Save CItemAttach Entities
			//-----------------------------------
			saveCItemAttachEntities(newitemList, newFieldList, bidderOrgIdList, itemAttachMap, bidderContentBeanMap, contentBean);
			
			//----------------------------------------------
			// Calculate Sub Total and save values into Map
			//----------------------------------------------
			Map<String, String> subTotalFormulaMap = new HashMap<>();
			if (("Y".equals(contentBean.getIsAlternativeOption())/* && !"Y".equals(contentBean.getIsOptionByBuyer())*/) 
					|| "Y".equals(contentBean.getIsFreeResponseForm())) {
				calcualteSubTotal(newitemList, newFieldList, responseValueMap, subTotalFormulaMap, bidderOrgIdList, bidderContentBeanMap, itmeIdAndOptionIdMap, contentBean);
			}
			
			//-----------------------------------
			// Save CItemAttribute Entities
			//-----------------------------------
			saveCItemAttrEntities(user, 
					   			  contentBean,
					   			  bidderContentBeanMap,
					   			  form, 
					   			  newitemList, 
					   			  newFieldList, 
					   			  bidderOrgIdList,
					   			  responseValueMap,
					   			  responseNoteMap,
					   			  subTotalFormulaMap,
					   			  uomMap,
					   			  quantityMap,
					   			  orgFormulaExpressionMap,
					   			  orgFormulaPositionMap,
					   			  itmeIdAndOptionIdMap);
			
			//-----------------------------------
			// Add a new field for "Selected To"
			//-----------------------------------
			Procurement procurement = procurementDAO.findProcurementByPrimaryKey(contentBean.getProcurementId(), user.getSysId(), user.getOrgId());
			if (!"Y".equals(procurement.getIsEvaluationCommitee())) {
				CFieldTemplate cFieldTemplate = cFieldTemplateService.findCFieldTemplateByTemplateType(user.getSysId(), user.getOrgId(), TemplateFieldType.EVAL_SELECT_TO.getCode());
				cFieldService.insertLastField(user, form.getContentsId(), newFieldList.get(newFieldList.size() - 1).getFieldId(), "right", TemplateFieldType.EVAL_SELECT_TO.getLabel(), FieldAttributeType.LISTMENU.getLabel(), cFieldTemplate.getTemplateId());
				cItemService.insertLastItem(user, procurement.getProcurementId(), form.getContentsId(), newitemList.get(newitemList.size() -1).getFormItemId(), "final", 1);
			}
		}
	}
	
	@Override
	@Transactional
	public void generateEvaluationModelAndView(ModelAndView mav, 
											   Integer sysId, 
											   Integer orgId, 
											   Integer accessId, 
											   Integer contentId) throws Exception {
		Set<CForm> forms = null;
		if (contentId == 0) {
			forms = cFormService.getCFormByAccessId(accessId);
		} else {
			CForm cform = cFormService.getCFormByAccessIdAndContentId(accessId, contentId);
			forms = new HashSet<CForm>();
			forms.add(cform);
		}
		
		CFieldTemplate cFieldTemplate = cFieldTemplateService.findCFieldTemplateByTemplateType(sysId, orgId, TemplateFieldType.EVAL_SELECT_TO.getCode());
		
		List<BidDocContentBean> formContentBeanList = new ArrayList<BidDocContentBean>();
		Map<Integer, List<TreeNode<BidDocItemBean>>> itemTreeMap = new HashMap<Integer, List<TreeNode<BidDocItemBean>>>();
		Map<Integer, Map<String, String>> attributeStatusMaps = new HashMap<Integer, Map<String, String>>();
		Map<Integer, Map<String, String>> attributeDefaultValueMaps = new HashMap<Integer, Map<String, String>>();
		Map<Integer, Map<String, String>> formulaMaps = new HashMap<Integer, Map<String, String>>();
		Map<Integer, Map<String, String>> noteMaps = new HashMap<Integer, Map<String, String>>();
		Map<Integer, Map<String, String>> editableMaps = new HashMap<Integer, Map<String, String>>();
		Map<Integer, List<BidderInfo>> bidderInfoMap = new HashMap<Integer, List<BidderInfo>>();
		Map<Integer, List<BidderInfo>> enableBidderInfoMap = new HashMap<Integer, List<BidderInfo>>();
		List<Integer> itemColCountList = new ArrayList<Integer>();
		List<Integer> contentIdList = new ArrayList<Integer>();
		Map<Integer, CItem> alternativeItemInfoMap = new HashMap<Integer, CItem>();
		
		// For item attachment
		//Map<Integer, Map<Integer, String>> itemAttachByContentIdMap = new HashMap<Integer, Map<Integer, String>>();

		Integer lastItemOptionId = new Integer(0);

		if (forms != null && forms.size() > 0) {
			for (CForm form: forms) {
				Integer contentsId = form.getContentsId();
				
				/* ****************************************
				 * Generate Common Form Contents Bean
				 ******************************************/
				BidDocContentBean formContentBean = new BidDocContentBean(form);
				formContentBeanList.add(formContentBean);
				
				/*setWidthMap(mav);
				setWidthOfField(form, mav);*/				
				
				/* ****************************************
				 * Generate Common Form Item Bean
				 ******************************************/			
				Set<CItem> currentItems = cItemService.getTreeSortedCItemByContentsId(sysId, orgId, contentsId);
				TreeUtil<BidDocItemBean> treeUtilBean = new TreeUtil<BidDocItemBean>();			
				
				/* ****************************************
				 * Generate Common Form Field Bean
				 ******************************************/
				Set<CField> formFields = form.getCFields();
				//Set<CField> formFields = cFieldService.getBidderGroupWithoutNoBidder(sysId, orgId, contentId);
				
				/* ****************************************
				 * Get Bidder Names (Only for Evaluation)
				 ******************************************/
				List<BidderInfo> bidderInfoList = cFieldService.getBidderNameList(sysId, orgId, contentsId, false);
				bidderInfoMap.put(contentsId, bidderInfoList);
				//logger.debug("[" + contentsId + "] bidderInfoList : " + bidderInfoList.toString());

				//Added by Daniel on May 10, 2018 - for supplier view
				List<BidderInfo> enableBidderInfoList = cFieldService.getBidderNameList(sysId, orgId, contentsId, true);
				enableBidderInfoMap.put(contentsId, enableBidderInfoList);

				/*
				 * Adjust column identifier if not exist
				 */
				String lastFrontColName = "B";
				if ("Y".equals(formContentBean.getIsItemNameHide())) {
					lastFrontColName = "A";
				}
				if ("Y".equals(formContentBean.getHasPricingItem())/* && !"Y".equals(formContentBean.getIsHidePricingField())*/) {
					if (!"Y".equals(formContentBean.getIsItemNameHide())) {
						lastFrontColName = "D";
					} else {
						lastFrontColName = "C";
					}
				}
				
				itemColCountList.add(FxUtils.cm_columnCharToNumber(lastFrontColName, 1) + 1);
				contentIdList.add(form.getContentsId());
				
				int colIdx = 1;
				List<BidDocFieldBean> bidDocFieldBeanList = new ArrayList<BidDocFieldBean>();
				
				Integer selectToFieldId = 0;
				if (!CollectionUtils.isEmpty(formFields)) {
					for (CField field: formFields) {
						/*
						 * Generate column identifier if not exist
						 */
						if (selectToFieldId == 0 && (field.getTemplateFieldId() != null && field.getTemplateFieldId().equals(cFieldTemplate.getTemplateId()))) {
							selectToFieldId = field.getFieldId();
						}
						int colNumber = FxUtils.cm_columnCharToNumber(lastFrontColName, 1);
						String colName = FxUtils.cm_columnNumberToChar(++colNumber, 1); 
						lastFrontColName = colName;
						if (StringUtils.isEmpty(field.getColumnIdentifier()) ||
								!field.getColumnIdentifier().equals(lastFrontColName)) {
							field.setColumnIdentifier(lastFrontColName);			
						}
						
						if (field.getListSeq() == null || field.getListSeq() != colIdx) {
							field.setListSeq(colIdx);
						}
						colIdx++;
						
						BidDocFieldBean fieldBean = new BidDocFieldBean();
						fieldBean.setCField(field);
						bidDocFieldBeanList.add(fieldBean);
					}
					formContentBean.setBidDocFieldBeanList(bidDocFieldBeanList);
				}
				
				boolean hasFormula = false;		
				if (!CollectionUtils.isEmpty(form.getItemAttributesWithFormula())) {
					hasFormula = true;
				}		
				
				for (CItem item : currentItems) {
					BidDocItemBean itemBean = new BidDocItemBean(item);
					treeUtilBean.add(itemBean.getItemId(), itemBean.getParentItemId(), itemBean);	
					if (item.getItemOptionId() != null) {
						lastItemOptionId = item.getItemOptionId();
					}		
					
					if (item.getAlternativeItemId() != null) {
                        Optional<CItem> foundItem = currentItems.stream()
                                                                .filter(i -> i.getOrgItemId() != null && i.getOrgItemId().intValue() == item.getAlternativeItemId().intValue())
                                                                .findFirst();
                        if (foundItem.isPresent()) {
                            alternativeItemInfoMap.put(item.getFormItemId(), foundItem.get());
                        }
					}
				}
				List<TreeNode<BidDocItemBean>> itemTree = treeUtilBean.serializeTree();
				itemTreeMap.put(contentsId, itemTree);
				
				// Map to calculate formula
				Map<Integer, Map<Integer, FxCellData>> formulaCalculationMap = new HashMap<Integer, Map<Integer, FxCellData>>();
				
				// For Item Attachment
				//Map<Integer, String> itemAttachMap = new HashMap<Integer, String>();

				int rowIdx = 1;
				for (TreeNode<BidDocItemBean> itemNode : itemTree) {
					if (itemNode.getObject() == null) { continue; }			
					CItem formitem = itemNode.getObject().getCItem();
					if (formitem == null) { continue; }			
					if (StringUtils.isEmpty(formitem.getRowIdentifier()) ||
							!formitem.getRowIdentifier().equals(String.valueOf(rowIdx))) {
						formitem.setRowIdentifier(String.valueOf(rowIdx));
						itemNode.getObject().setRowIdentifier(String.valueOf(rowIdx));
					}
					if (formitem.getListSeq() == null || formitem.getListSeq() != rowIdx) {
						formitem.setListSeq(rowIdx);
					}
					rowIdx++;
					
					/* ***************************************************
					 * Create cell matrix map to calculate formula
					 ****************************************************/
					if(hasFormula) {
						bidDocItemFormulaService.generateFormulaMap(form, formitem, formulaCalculationMap, null);
					}
					
					//itemAttachMap.put(formitem.getFormItemId(), formitem.getCItemAttach().size() > 0 ? "Y" : "N");
				}
				
				if (hasFormula) {
					bidDocItemFormulaService.convertFxDataForParentItem(itemTree, formulaCalculationMap);
					FxEvaluator.evaluate(form.getContentsId(), formulaCalculationMap);
				}
				
				Map<String, String> attributeStatusMap = new HashMap<String, String>();
				Map<String, String> attributeDefaultValueMap = new HashMap<String, String>();
				Map<String, String> formulaMap = new HashMap<String, String>();
				Map<String, String> noteMap = new HashMap<String, String>();
				Map<String, String> editableMap = new HashMap<String, String>();
				
				Set<CItemAttribute> cItemAttrList = cItemAttributeService.getCItemAttributeByContentsId(sysId, orgId, form.getContentsId());
				for (CItemAttribute cItemAttr : cItemAttrList) {
					//logger.debug("{}", cItemAttr);
					attributeStatusMap.put(cItemAttr.getFormItemId() + "_" + cItemAttr.getFieldId(), cItemAttr.getStatus());
					noteMap.put(cItemAttr.getFormItemId() + "_" + cItemAttr.getFieldId(), cItemAttr.getNote());
					editableMap.put(cItemAttr.getFormItemId() + "_" + cItemAttr.getFieldId(), cItemAttr.getIsEditable());
					//For formula
					if (hasFormula && cItemAttr.getFormula() != null && !"".equals(cItemAttr.getFormula().trim())) {
						formulaMap.put(cItemAttr.getFormItemId() + "_" + cItemAttr.getFieldId(), cItemAttr.getFormula());  
						int rowIdentifier = cItemAttr.getCItem().getRowIdentifier() != null ? Integer.parseInt(cItemAttr.getCItem().getRowIdentifier()) : 1;
						
						Map<Integer, FxCellData> formulaCellMap = formulaCalculationMap.get(rowIdentifier);
						
						if (formulaCellMap != null) {
							FxCellData fxCellData =  formulaCellMap.get(FxUtils.cm_columnCharToNumber(cItemAttr.getCField().getColumnIdentifier(), 1));
							attributeDefaultValueMap.put(cItemAttr.getFormItemId() + "_" + cItemAttr.getFieldId(), fxCellData != null ? fxCellData.getValue() : "");
						}
					} else {
						if (cItemAttr.getFieldId().equals(selectToFieldId)) {
							attributeDefaultValueMap.put(cItemAttr.getFormItemId() + "_" + cItemAttr.getFieldId(), (cItemAttr.getSuplId() == null ? null : String.valueOf(cItemAttr.getSuplId())));
						} else {
							attributeDefaultValueMap.put(cItemAttr.getFormItemId() + "_" + cItemAttr.getFieldId(), cItemAttr.getDefaultValue());
						}
					}			
				}
				
				attributeStatusMaps.put(contentsId, attributeStatusMap);
				attributeDefaultValueMaps.put(contentsId, attributeDefaultValueMap);
				//logger.debug("{}", (attributeDefaultValueMaps.get(1925)));
				formulaMaps.put(contentsId, formulaMap);
				noteMaps.put(contentsId, noteMap);
				editableMaps.put(contentsId, editableMap);
				//itemAttachByContentIdMap.put(contentsId, itemAttachMap);
			}
		}
		
		mav.addObject("formContentBeanList", formContentBeanList);
		mav.addObject("itemTreeMap", itemTreeMap);			
		mav.addObject("procurementId", accessId);
		mav.addObject("isAlternativeOption", lastItemOptionId.equals(0) ? "N" : "Y");
		mav.addObject("attributeStatusMaps", attributeStatusMaps);
		mav.addObject("attributeDefaultValueMaps", attributeDefaultValueMaps);
		mav.addObject("formulaMaps", formulaMaps);
		mav.addObject("noteMaps", noteMaps);
		mav.addObject("editableMaps", editableMaps);
		mav.addObject("bidderInfoMap", bidderInfoMap);
		mav.addObject("enableBidderInfoMap", enableBidderInfoMap);
		mav.addObject("itemColCountList", itemColCountList);
		mav.addObject("contentIdList", contentIdList);
		mav.addObject("alternativeItemInfoMap", alternativeItemInfoMap);
		//mav.addObject("itemAttachByContentIdMap", itemAttachByContentIdMap);
	}
	
	@SuppressWarnings("unchecked")
	@Override
	@Transactional
	public void generateWBSModelAndView(ModelAndView mav, 
										User user,
									    Integer sysId, 
										Integer orgId, 
										Integer wbsId, 
										Integer cformContentId,
										ProcurementInfoBean procurementInfoBean) throws Exception {
		CForm cform = null;
		if (cformContentId == 0) {
			Set<CForm> forms = cFormService.getCFormByAccessId(wbsId);
			if (forms != null && forms.size() > 0) {
				cform = forms.iterator().next();
			}
		} else {
			cform = cFormService.getCFormByAccessIdAndContentId(wbsId, cformContentId);
		}
		
		BidDocContentBean formContentBean = new BidDocContentBean();
		List<TreeNode<BidDocItemBean>> itemTree = new ArrayList<TreeNode<BidDocItemBean>>();
		Map<String, String> attributeStatusMap = new HashMap<String, String>();
		Map<String, String> attributeDefaultValueMap = new HashMap<String, String>();
		Map<String, String> formulaMap = new HashMap<String, String>();
		Map<Integer, Procurement> biddocumentMap = new HashMap<Integer, Procurement>();
		Map<Integer, List<CommonBidderAwardInfo>> commonBidderAwardInfoMap = new HashMap<Integer, List<CommonBidderAwardInfo>>();
		Map<Integer, List<String>> responseFormIdMap = new HashMap<Integer, List<String>>();
		
		//MasterCode materCode = masterCodeDAO.findMasterCodesByMasterCodeField(sysId, orgId, 0, MasterCodeType.UOM.getCode());
		MasterCode materCode = masterCodeDAO.findByMasterCodeField(sysId, orgId, 0, MasterCodeType.UOM.getCode());

		if (cform != null) {			
			Integer contentsId = cform.getContentsId();
			
			/* ****************************************
			 * Generate Common Form Contents Bean
			 ******************************************/
			formContentBean = new BidDocContentBean(cform);
			
			/* ****************************************
			 * Generate Common Form Item Bean
			 ******************************************/			
			Set<CItem> currentItems = cItemService.getTreeSortedCItemByContentsId(sysId, orgId, contentsId);
			TreeUtil<BidDocItemBean> treeUtilBean = new TreeUtil<BidDocItemBean>();			
			
			/* ****************************************
			 * Generate Common Form Field Bean
			 ******************************************/
			Set<CField> formFields = cform.getCFields();
			//Set<CField> formFields = cFieldService.getBidderGroupWithoutNoBidder(sysId, orgId, contentId);
			
			/*
			 * Adjust column identifier if not exist
			 */
			String lastFrontColName = "B";
			if ("Y".equals(formContentBean.getIsItemNameHide())) {
				lastFrontColName = "A";
			}
			if ("Y".equals(formContentBean.getHasPricingItem())/* && !"Y".equals(formContentBean.getIsHidePricingField())*/) {
				if (!"Y".equals(formContentBean.getIsItemNameHide())) {
					lastFrontColName = "D";
				} else {
					lastFrontColName = "C";
				}
			}
			
			int colIdx = 1;
			List<BidDocFieldBean> bidDocFieldBeanList = new ArrayList<BidDocFieldBean>();
			if (!CollectionUtils.isEmpty(formFields)) {
				for (CField field: formFields) {
					/*
					 * Generate column identifier if not exist
					 */
					int colNumber = FxUtils.cm_columnCharToNumber(lastFrontColName, 1);
					String colName = FxUtils.cm_columnNumberToChar(++colNumber, 1); 
					lastFrontColName = colName;
					if (StringUtils.isEmpty(field.getColumnIdentifier()) ||
							!field.getColumnIdentifier().equals(lastFrontColName)) {
						field.setColumnIdentifier(lastFrontColName);			
					}
					
					if (field.getListSeq() == null || field.getListSeq() != colIdx) {
						field.setListSeq(colIdx);
					}
					colIdx++;
					
					BidDocFieldBean fieldBean = new BidDocFieldBean();
					fieldBean.setCField(field);
					bidDocFieldBeanList.add(fieldBean);
					
					List<BidDocFieldAttributeBean> bidDocFieldAttributeBeanList = new ArrayList<BidDocFieldAttributeBean>();
					Set<CFieldAttributes> cFieldsAttributes = field.getcFieldAttributes();
					for (CFieldAttributes cFieldsAttribute: cFieldsAttributes) {
						BidDocFieldAttributeBean bidDocFieldAttributeBean = new BidDocFieldAttributeBean();
						bidDocFieldAttributeBean.setCFieldAttribute(cFieldsAttribute);					
						bidDocFieldAttributeBeanList.add(bidDocFieldAttributeBean);				
					}
					fieldBean.setBidDocFieldAttributeBeanList(bidDocFieldAttributeBeanList);
				}
				formContentBean.setBidDocFieldBeanList(bidDocFieldBeanList);
			}
			
			boolean hasFormula = false;		
			if (!CollectionUtils.isEmpty(cform.getItemAttributesWithFormula())) {
				hasFormula = true;
			}		
			
			for (CItem item : currentItems) {
				BidDocItemBean itemBean = new BidDocItemBean(item);
				treeUtilBean.add(itemBean.getItemId(), itemBean.getParentItemId(), itemBean);
				
				if (!StringUtils.isEmpty(item.getResponseFormIds())) {
					List<String> contentList = new ArrayList<String>(Arrays.asList(StringUtils.split(item.getResponseFormIds(),",")));
					responseFormIdMap.put(item.getFormItemId(), contentList);
				}				
			}
			
			itemTree = treeUtilBean.serializeTree();
			
			// Map to calculate formula
			Map<Integer, Map<Integer, FxCellData>> formulaCalculationMap = new HashMap<Integer, Map<Integer, FxCellData>>();

			int rowIdx = 1;
			for (TreeNode<BidDocItemBean> itemNode : itemTree) {
				if (itemNode.getObject() == null) { continue; }		
				
				CItem formitem = itemNode.getObject().getCItem();
				if (formitem == null) { continue; }			
				if (StringUtils.isEmpty(formitem.getRowIdentifier()) ||
						!formitem.getRowIdentifier().equals(String.valueOf(rowIdx))) {
					formitem.setRowIdentifier(String.valueOf(rowIdx));
					itemNode.getObject().setRowIdentifier(String.valueOf(rowIdx));
				}
				if (formitem.getListSeq() == null || formitem.getListSeq() != rowIdx) {
					formitem.setListSeq(rowIdx);
				}
				
				List<CommonBidderAwardInfo> awardInfoList = new ArrayList<CommonBidderAwardInfo>();
				
				if (ResponseFormType.SELF.getCode().equals(formitem.getResponseFormType())) {
					CommonBidderAwardInfo awardInfo = new CommonBidderAwardInfo();
					awardInfo.setSuplid(0);
					awardInfo.setBidderName(user.getOrgName());
					awardInfo.setContactPerson("");
					awardInfo.setAdderss("");
					
					awardInfoList.add(awardInfo);	
					commonBidderAwardInfoMap.put(formitem.getFormItemId(), awardInfoList);
				} else if (ResponseFormType.WBS.getCode().equals(formitem.getResponseFormType())) {
					Procurement procurement = procurementDAO.findProcurementByPrimaryKey(formitem.getAccessId(), sysId, orgId);
					biddocumentMap.put(formitem.getFormItemId(), procurement);

					// Get Final Award Info
					//commonBidderAwardInfoMap.put(formitem.getFormItemId(), getFinalAwardee(sysId, orgId, formitem.getAccessId(), procurementInfoBean));
					List<String> awardedBidderList = new ArrayList<String>();					
					if (!StringUtils.isEmpty(formitem.getAwardedBidders())) {
						awardedBidderList = new ArrayList<String>(Arrays.asList(StringUtils.split(formitem.getAwardedBidders(),",")));
						Tender tender = tenderDAO.findTenderByPrimaryKey(sysId, orgId, formitem.getAccessId());
						awardedBidderList.stream().forEach(bidderId -> {
							TenderBidder tenderBidder;
							try {
								tenderBidder = tenderBidderDAO.findTenderBidderByPrimaryKey(sysId, orgId, formitem.getAccessId(), tender.getTenderId(), Integer.valueOf(bidderId));
								
								CommonBidderAwardInfo awardInfo = new CommonBidderAwardInfo();
								awardInfo.setSuplid(tenderBidder.getBidderOrgId());
								awardInfo.setBidderName(tenderBidder.getBidderName());
								awardInfo.setContactPerson(tenderBidder.getFirstname() + " " + tenderBidder.getLastname());
								awardInfo.setAdderss(tenderBidder.getFullAddress());
								
								awardInfoList.add(awardInfo);																
							} catch (Exception e) {
								e.printStackTrace();
							}
							
							commonBidderAwardInfoMap.put(formitem.getFormItemId(), awardInfoList);
						});
					} 
				}
				
				rowIdx++;
				
				/* ***************************************************
				 * Create cell matrix map to calculate formula
				 ****************************************************/
				if (hasFormula) {
					bidDocItemFormulaService.generateFormulaMap(cform, formitem, formulaCalculationMap, null);
				}
			}
			
			if (hasFormula) {
				bidDocItemFormulaService.convertFxDataForParentItem(itemTree, formulaCalculationMap);
				FxEvaluator.evaluate(cform.getContentsId(), formulaCalculationMap);
			}
			
			Set<CItemAttribute> cItemAttrList = cItemAttributeService.getCItemAttributeByContentsId(sysId, orgId, cform.getContentsId());
			for (CItemAttribute cItemAttr : cItemAttrList) {
				attributeStatusMap.put(cItemAttr.getFormItemId() + "_" + cItemAttr.getFieldId(), cItemAttr.getStatus());			
				//For formula
				if (hasFormula && cItemAttr.getFormula() != null && !"".equals(cItemAttr.getFormula().trim())) {
					formulaMap.put(cItemAttr.getFormItemId() + "_" + cItemAttr.getFieldId(), cItemAttr.getFormula());  
					int rowIdentifier = cItemAttr.getCItem().getRowIdentifier() != null ? Integer.parseInt(cItemAttr.getCItem().getRowIdentifier()) : 1;
					
					Map<Integer, FxCellData> formulaCellMap = formulaCalculationMap.get(rowIdentifier);
					
					if (formulaCellMap != null) {
						FxCellData fxCellData =  formulaCellMap.get(FxUtils.cm_columnCharToNumber(cItemAttr.getCField().getColumnIdentifier(), 1));
						attributeDefaultValueMap.put(cItemAttr.getFormItemId() + "_" + cItemAttr.getFieldId(), fxCellData != null ? fxCellData.getValue() : "");
					}
				} else {
					attributeDefaultValueMap.put(cItemAttr.getFormItemId() + "_" + cItemAttr.getFieldId(), cItemAttr.getDefaultValue());
				}			
			}
			
			mav.addObject("templateFiels", cFieldTemplateService.getCFieldTemplateByResponseFormType(sysId, orgId, cform.getApplicationType()));
		}
		
		Wbs wbs = wbsService.findWbsByPrimaryKey(sysId, orgId, wbsId);
		String procurementIds = "";
		if (!StringUtils.isEmpty(wbs.getProcurementIds())) {
			List<String> procurementList = new ArrayList<String>(Arrays.asList(StringUtils.split(wbs.getProcurementIds(),",")));
			procurementIds = StringUtils.join(procurementList, ",");
		}
		
		mav.addObject("biddocumentMap", biddocumentMap);
		mav.addObject("bidderAwardInfoMap", commonBidderAwardInfoMap);
		
		mav.addObject("commonFormBean", formContentBean);
		mav.addObject("itemTree", itemTree);
		mav.addObject("wbsId", wbsId);		
		mav.addObject("procurementIds", procurementIds);		
		mav.addObject("cformContentId", cform != null ? cform.getContentsId() : 0);
		mav.addObject("attributeStatusMap", attributeStatusMap);
		mav.addObject("attributeDefaultValueMap", attributeDefaultValueMap);
		mav.addObject("formulaMap", formulaMap);
		mav.addObject("materCode", materCode);
		mav.addObject("responseFormIdMap", responseFormIdMap);
	}
	
	@Override
	public CForm saveFormEntity(User user, BidDocContentBean contentBean, String applicationType) throws Exception {
		CForm form = new CForm();
		form.setSysId(user.getSysId());
		form.setOrgId(user.getOrgId());
		// Auto generation for id
		//form.setContentsId(contentBean.getContentId());
		form.setContentName(contentBean.getContentName());
		form.setContentNumber(contentBean.getContentNumber());
		form.setOrgContentsId(contentBean.getContentId());
		form.initValue(user);
		//form.setDescription(contentBean.getReponseDescription());
		form.setDescription(null);
		form.setActionType(contentBean.getActionType());
		form.setParentContentId(0);
		//form.setParentContentId(contentBean.getParentContentid());
		form.setDepth(contentBean.getDepth());
		if ("Y".equals(contentBean.getIsFreeResponseForm())) {
			form.setHasPricingItem("N");
		} else {
			form.setHasPricingItem(contentBean.getHasPricingItem());
			/*form.setIsHidePricingField(contentBean.getIsHidePricingField());*/
		}
		form.setListSeq(contentBean.getListSeq());
		form.setIsItemNameHide(contentBean.getIsItemNameHide());
		form.setAccessId(contentBean.getProcurementId());
		form.setSubAccessId(contentBean.getSubAccessId() == null ? 0 : contentBean.getSubAccessId());
		form.setApplicationType(applicationType);
		form.setIsSelcetedEvalContent("Y");
		
		return cFormService.saveCForm(form);
	}

	@Override
	public <T extends Object> CItem getItemEntity(User user, 
							   					  CForm form, 
							   					  T obj, 
							   					  Integer ItemListSeq,
							   					  Integer parentItemId,
							   					  boolean isBidResponseFormId) throws Exception {

		CItem item = new CItem();
		item.setSysId(form.getSysId());
		item.setOrgId(form.getOrgId());
		item.setContentsId(form.getContentsId());
		item.initValue(user);
		item.setListSeq(ItemListSeq);
		item.setRowIdentifier(String.valueOf(ItemListSeq));
		item.setIsEditable("N");

		if (obj instanceof BidDocItemBean) {
			
			item.setFormItemId(((BidDocItemBean) obj).getItemId());
			if (isBidResponseFormId) {
				item.setOrgItemId(((BidDocItemBean) obj).getItemId());
			} else {
				item.setOrgItemId(ItemListSeq);
			}
			item.setOrgParentItemId(((BidDocItemBean) obj).getParentItemId());
			
			if (parentItemId != 0) {
				item.setParentItemId(parentItemId);
			} else {
				item.setParentItemId(((BidDocItemBean) obj).getParentItemId());
			}
			
			item.setItemNumber(((BidDocItemBean) obj).getItemNumber());
			item.setItemName(((BidDocItemBean) obj).getItemName());
			item.setDescription(((BidDocItemBean) obj).getDescription());
			item.setActionType(((BidDocItemBean) obj).getActionType());

			item.setDepth(((BidDocItemBean) obj).getDepth());
			item.setUom(((BidDocItemBean) obj).getUom());
			//item.setItemCode(itemBean.getItemCode());
			item.setQuantity(((BidDocItemBean) obj).getQuantity());
			item.setUnitPrice(((BidDocItemBean) obj).getUnitPrice());
			item.setAmount(((BidDocItemBean) obj).getAmount());
			item.setIsTotalRowItem(((BidDocItemBean) obj).getIsTotalRowItem());
			
			if (((BidDocItemBean) obj).getItemOptionId() != null ) {
				item.setItemOptionId(((BidDocItemBean) obj).getItemOptionId());
			} else {
				item.setItemOptionId(0);
			}			
		} else if (obj instanceof BidDocResponseItemBean) {
			
			item.setFormItemId(((BidDocResponseItemBean) obj).getItemId());
			if (isBidResponseFormId) {
				item.setOrgItemId(((BidDocResponseItemBean) obj).getItemId());
			} else {
				item.setOrgItemId(ItemListSeq);
			}
			item.setOrgParentItemId(((BidDocResponseItemBean) obj).getParentItemId());
			
			if (parentItemId != 0) {
				item.setParentItemId(parentItemId);
			} else {
				item.setParentItemId(((BidDocResponseItemBean) obj).getParentItemId());
			}
			
			item.setItemNumber(((BidDocResponseItemBean) obj).getItemNumber());
			item.setItemName(((BidDocResponseItemBean) obj).getItemName());
			item.setDescription(((BidDocResponseItemBean) obj).getItemDescription());
			item.setActionType(((BidDocResponseItemBean) obj).getActionType());

			item.setDepth(((BidDocResponseItemBean) obj).getDepth() == null ? 1 : ((BidDocResponseItemBean) obj).getDepth());
			item.setUom(((BidDocResponseItemBean) obj).getUom());
			if (((BidDocResponseItemBean) obj).getQuantity() == null ||
					"".equals(((BidDocResponseItemBean) obj).getQuantity())) {
				item.setQuantity(null);
			} else {
				item.setQuantity(new BigDecimal(((BidDocResponseItemBean) obj).getQuantity()));
			}
			item.setUnitPrice(((BidDocResponseItemBean) obj).getUnitPrice());
			item.setAmount(((BidDocResponseItemBean) obj).getAmount());
			item.setIsTotalRowItem(((BidDocResponseItemBean) obj).getIsTotalRowItem());
			
	        //item.setAlternativeItemId(Integer.valueOf(((BidDocResponseItemBean) obj).getAlternativeItemId()));
			item.setAlternativeItemId(((BidDocResponseItemBean) obj).getAlternativeItemId() != null ? Integer.valueOf(((BidDocResponseItemBean) obj).getAlternativeItemId()) : null);
			
			if (((BidDocResponseItemBean) obj).getItemOptionId() != null ) {
				item.setItemOptionId(((BidDocResponseItemBean) obj).getItemOptionId());
			} else {
				item.setItemOptionId(0);
			}
		}
		
		return item;
	}
	
	@Override
	public <T extends Object> CItem getParentItemEntity(User user, 
							   		 CForm form, 
							   		 T obj,
							   		 Integer itemListSeq,
							   		 String itemNumber) throws Exception {
		
		CItem item = new CItem();
		item.setSysId(form.getSysId());
		item.setOrgId(form.getOrgId());
		item.setContentsId(form.getContentsId());
		item.setFormItemId(0);
		item.setParentItemId(0);
		item.setOrgItemId(itemListSeq);
		item.setItemNumber(itemNumber);
		item.setDepth(0);
		item.initValue(user);
		item.setListSeq(itemListSeq);
		item.setRowIdentifier(String.valueOf(itemListSeq));
		item.setIsEditable("N");
		
		if (obj == null) {
			item.setItemOptionId(0);
		} else if (obj instanceof BidDocItemBean) {
			item.setItemOptionId(((BidDocItemBean) obj).getItemOptionId());
		} else if (obj instanceof BidDocResponseItemBean) {
			item.setItemOptionId(((BidDocResponseItemBean) obj).getItemOptionId());
		}

		return item;
	}
	
	@Override
	public CItem getSubTotalItemEntity(User user, 
							   		   CForm form, 
							   		   Integer itemListSeq,
							   		   Integer parentItemId) throws Exception {
		
		CItem item = new CItem();
		item.setSysId(form.getSysId());
		item.setOrgId(form.getOrgId());
		item.setContentsId(form.getContentsId());
		item.setFormItemId(0);
		item.setParentItemId(parentItemId);
		item.setOrgItemId(itemListSeq);
		item.setIsTotalRowItem("Y");
		item.setItemNumber("Sub Total");
		item.setItemName("Sub Total");
		item.setDepth(1);
		item.initValue(user);
		item.setListSeq(itemListSeq);
		item.setRowIdentifier(String.valueOf(itemListSeq));
		item.setIsEditable("N");
		
		return item;
	}

	@Override
	public CField getFieldEntity(User user, 
								 CForm form, 
								 BidDocFieldBean fieldBean, 
								 String bidderName,
								 Integer bidderId,
								 Integer fieldListSeq,
								 Integer bidderListSeq) throws Exception {
		CField field = new CField();
		field.setSysId(form.getSysId());
		field.setOrgId(form.getOrgId());
		field.setContentsId(form.getContentsId());
		field.setOrgFieldId(fieldBean.getFieldId());
		field.setFieldName(fieldBean.getFieldName());
		field.setDescription(fieldBean.getDescription());
		field.setDefaultValue(fieldBean.getDefaultValue());
		// Label/Radio Button/Check Box/List/Menu/Number : No need here
		field.setAttributeType(fieldBean.getAttributeType());
		field.initValue(user);
		// ActionType : UPDATE, ADD, DELETE (ActionType.ADD.getCode())
		field.setActionType(fieldBean.getActionType());
		field.setIsDefault(fieldBean.getIsDefault());
		field.setListSeq(fieldListSeq);
		field.setTarget(fieldBean.getTarget());
		field.setPreDefinedScoreYn(fieldBean.getPreDefinedScoreYn());
		field.setColumnIdentifier(fieldBean.getColumnIdentifier());
		
		if ("Y".equals(fieldBean.getIsFormula())) {
			field.setIsFormula(fieldBean.getIsFormula());
			field.setFormula(fieldBean.getFormula());
		} else {
			field.setIsFormula("N");		
			field.setFormula(null);			
		}
		
		field.setIsCurrency(fieldBean.getIsCurrency());
		field.setIsHiddenColumn(fieldBean.getIsHiddenColumn());
		field.setBidderName(bidderName);
		field.setBidderId(bidderId);
		field.setBidderListseq(bidderListSeq);
		field.setIsEditable("N");
		field.setIsBatchField("N");
		field.setIsPercentage(fieldBean.getIsPercentage());
		
		return field;
	}
	
	@Override
	public CItemAttribute getItemAttributeEntity(User user,
										   		CForm form,
										   		CItem item,
										   		CField field,										   
										   		BidDocContentBean contentBean,
										   		String defaultValue,
										   		String note,
										   		String formula) throws Exception {
		
		StringBuilder sbKey = new StringBuilder();
		//sbKey.append(item.getFormItemId())
		sbKey.append(item.getOrgItemId())
			 .append("_")
			 .append(field.getOrgFieldId());
		
		CItemAttribute itemAttr = new CItemAttribute();
		itemAttr.setSysId(form.getSysId());
		itemAttr.setOrgId(form.getOrgId());
		itemAttr.setContentsId(form.getContentsId());
		itemAttr.setFormItemId(item.getFormItemId());
		itemAttr.setFieldId(field.getFieldId());
		itemAttr.setFormula(formula);
		
		if (contentBean != null && defaultValue == null) {
			String value = contentBean.getFormulaValueMap().get(sbKey.toString());
			itemAttr.setDefaultValue("Unit Price".equals(value) ? "" : value);
			itemAttr.setStatus(contentBean.getAttributeStatusMap().get(sbKey.toString()));
			itemAttr.setNote(contentBean.getNoteMap().get(sbKey.toString()));
			
			itemAttr.setIsEditable(StringUtils.isEmpty(value) || "Unit Price".equals(value) ? "Y" : "N");
		} else if (defaultValue != null) {
			itemAttr.setDefaultValue(defaultValue);
			itemAttr.setStatus("");
			itemAttr.setNote(note);
			
			itemAttr.setIsEditable(StringUtils.isEmpty(defaultValue) ? "Y" : "N");
		}
		
		/*if (StringUtils.isEmpty(defaultValue)) {
			if ("Y".equals(contentBean.getIsFreeResponseForm())) {
				if ("UOM".equals(field.getFieldName())
					&& "UOM_EX".equals(field.getDescription())) {
					itemAttr.setDefaultValue(item.getUom());
				} else if ("Quantity".equals(field.getFieldName())
					&& "Quantity_EX".equals(field.getDescription())) {
					itemAttr.setDefaultValue(item.getQuantity() == null ? "" : item.getQuantity().toString());
				}
			}
		}*/
		
		itemAttr.initValue(user);
		//itemAttr.setFormula(contentBean.getFormulaMap().get(sbKey.toString()));
		//itemAttr.setFormula(null);
		
		//itemAttr.setAttributeValue(itemAttributeBean.getAttributeValue());		
		// Label/Radio Button/Check Box/List/Menu/Number : No need here
		//itemAttr.setAttributeType(itemAttributeBean.getAttributeType());
		
		return itemAttr;
	}
	

	@Transactional
	public Integer createWBSForm(User user, 
							  	Integer sysId, 
							  	Integer orgId, 
							  	Integer accessId, 
							  	Integer contentId, 
							  	int rowCount, 
							  	int columnCount) throws Exception {
		if (contentId == 0) {
			Wbs wbs = wbsService.findWbsByPrimaryKey(sysId, orgId, accessId);
			
			CForm newForm = new CForm();
			newForm.setSysId(user.getSysId());
			newForm.setOrgId(user.getOrgId());
			newForm.setContentName(wbs.getWbsName());
			newForm.setContentNumber(wbs.getWbsNumber());
			newForm.setOrgContentsId(0);
			newForm.initValue(user);
			newForm.setDescription(wbs.getWbsDesc());
			newForm.setActionType(null);
			newForm.setParentContentId(0);
			newForm.setDepth(1);
			newForm.setHasPricingItem("N");
			newForm.setListSeq(1);
			newForm.setIsItemNameHide("N");
			newForm.setAccessId(accessId);
			newForm.setSubAccessId(accessId);
			newForm.setApplicationType(ResponseFormType.WBS.getLabel());
			
			newForm = cFormService.saveCForm(newForm);
			contentId = newForm.getContentsId();
		}
		
		int fieldActionType = 0; //0: no change, 1: delete, 2: add
		int itemActionType = 0;  //same as fieldActionType
		
		Set<CField> fieldList = new HashSet<CField>();
		Map<Integer, Integer> deletedFieldMap = new HashMap<Integer, Integer>();

		cFieldService.getCFieldByContentId(sysId, orgId, contentId).stream()
		  				  /*.filter(field -> "Y".equals(field.getIsTemplateField()))*/
		  				  .forEach(field -> {
		  					  cFieldService.deleteCField(field);
		  					  deletedFieldMap.put(field.getFieldId(), field.getFieldId());
		  				  });
		entityManager.clear();

		/* ***************************************************
		 * Validate if some fields should be added or deleted.
		 *****************************************************/
		Set<CField> existingFieldsList = cFieldService.getCFieldByContentId(sysId, orgId, contentId);
		if (existingFieldsList == null || existingFieldsList.size() == 0) {
			if (columnCount > 0) {
				fieldActionType = 2;
			}
		} else {			
			if (existingFieldsList.size() > columnCount) {
				fieldActionType = 1;
			} else if (existingFieldsList.size() < columnCount) {
				fieldActionType = 2;
			}
		}
		
		/* ***************************************************
		 * Validate if some items should be added or deleted.
		 *****************************************************/
		Set<CItem> existingItems = cItemService.getItemsByContentsId(sysId, orgId, contentId);
		if (existingItems == null || existingItems.size() == 0) {
			if (rowCount > 0) {
				itemActionType = 2;
			}
		} else {			
			if (existingItems.size() > rowCount) {
				itemActionType = 1;
			} else if (existingItems.size() < rowCount) {
				itemActionType = 2;
			}
		}
		
		/* **************************************************
		 * Save dynamic fields (default attribute is label)
		 ***************************************************/
		fieldList.addAll(existingFieldsList);
		
		// Create template Fields for WBS
		int lastSeq = cFieldService.getMaxListSeq(sysId, orgId, contentId);
		Set<CFieldTemplate> fieldTemplates = cFieldTemplateService.getCFieldTemplateByResponseFormType(sysId, orgId, ResponseFormType.WBS.getLabel());
		for (CFieldTemplate template : fieldTemplates) {
			CField newDynamicField = new CField();
			newDynamicField.setSysId(sysId);
			newDynamicField.setOrgId(orgId);
			newDynamicField.setContentsId(contentId);
			newDynamicField.setFieldName(template.getFieldName());
			newDynamicField.setDescription("");
			newDynamicField.setDefaultValue("");
			newDynamicField.setAttributeType(template.getAttributeType());
			newDynamicField.initValue(user);
			newDynamicField.setListSeq(lastSeq++);
			newDynamicField.setColumnIdentifier(StringUtil.getColIndentifier(newDynamicField.getListSeq()-1));
			newDynamicField.setIsFormula("N");
			newDynamicField.setFormula(template.getFormula());
			newDynamicField.setIsHiddenColumn("N");
			newDynamicField.setIsEditable("Y");
			newDynamicField.setIsBatchField("N");
			newDynamicField.setIsAwardee("Awarded To".equals(template.getFieldName()) ? "Y" : "N");
			newDynamicField.setIsTemplateField("Y");
			newDynamicField.setTemplateFieldId(template.getTemplateId());
			newDynamicField.setTarget("Buyer");
			newDynamicField.setIsCurrency(template.getIsCurrency());
			newDynamicField.setIsFormula(template.getIsFormula());
			newDynamicField.setActionType(ActionType.ADD.getCode());
			
			newDynamicField = cFieldService.saveCField(newDynamicField);
			fieldList.add(newDynamicField);
			
			cItemAttributeService.saveItemAttributeByCField(user, existingItems, newDynamicField, true);					
		}
		
		if (columnCount > 0) {
			if (fieldActionType == 2) {
				for (int i = 0; i < columnCount - existingFieldsList.size(); i++) {			
					CField newDynamicField = new CField();
					newDynamicField.setSysId(sysId);
					newDynamicField.setOrgId(orgId);
					newDynamicField.setContentsId(contentId);
					newDynamicField.setFieldName("");
					newDynamicField.setDescription("");
					newDynamicField.setDefaultValue("");
					newDynamicField.setAttributeType("Number");
					newDynamicField.setIsCurrency("Y");
					newDynamicField.initValue(user);
					newDynamicField.setListSeq(lastSeq++);
					newDynamicField.setColumnIdentifier(StringUtil.getColIndentifier(newDynamicField.getListSeq()-1));
					newDynamicField.setIsFormula("N");
					newDynamicField.setFormula(null);
					newDynamicField.setIsHiddenColumn("N");
					newDynamicField.setIsEditable("Y");
					newDynamicField.setIsBatchField("N");
					newDynamicField.setIsAwardee("N");
					newDynamicField.setIsTemplateField("N");
					newDynamicField.setTarget("Buyer");
					newDynamicField.setActionType(ActionType.ADD.getCode());
					
					newDynamicField = cFieldService.saveCField(newDynamicField);
					fieldList.add(newDynamicField);
					
					cItemAttributeService.saveItemAttributeByCField(user, existingItems, newDynamicField, true);
				}
				
				//cFieldService.flush();
			} else if (fieldActionType == 1) {
				int deleteIndex = 0;
				
				fieldList = new HashSet<CField>();
				for (CField field: existingFieldsList) {			
					deleteIndex++;				
					if (deleteIndex > columnCount) {
						//existingFieldsList.remove(field);
						cFieldService.deleteCField(field);
						deletedFieldMap.put(field.getFieldId(), field.getFieldId());
					} else {
						fieldList.add(field);
					}
				}			
			}
			
			//cFieldService.updateCloumnIdentifier(user, sysId, orgId, contentId);
		}
		
		/* **************************************************
		 * Save dynamic Items (default attribute is label)
		 ***************************************************/
		if (rowCount > 0) {
			
			if (itemActionType == 2) {
				//Add items			
				Integer maxListSeq = cItemService.getItemMaxListSeq(sysId, orgId, contentId);
				//Integer newItemid = bidDocumentsFormItemsDAO.getNextItemId(sysId, orgId, procurementId);
				
				for (int i = 0; i < rowCount - existingItems.size() ; i++) {		
					CItem newItem = new CItem();
					newItem.setSysId(sysId);
					newItem.setOrgId(orgId);
					newItem.setContentsId(contentId);
					newItem.setItemNumber("");
					newItem.setItemName("");			
					newItem.initValue(user);
					newItem.setParentItemId(0);
					newItem.setDepth(1);		
					newItem.setListSeq(++maxListSeq);
					newItem.setRowIdentifier(String.valueOf(newItem.getListSeq()));
					newItem = cItemService.saveCItem(newItem);
				
					/* *********************************************
					 * Save item Attribute based on item created newly
					 ***********************************************/
					cItemAttributeService.saveItemAttributeByCItem(user, newItem, fieldList, true, null, null);
				}
			} else if (itemActionType == 1) {
				//Create tree structure.
				TreeUtil<CItem> itemTreeUtil = new TreeUtil<CItem>();
				for (CItem item: existingItems) {				
					itemTreeUtil.add(item.getFormItemId(), item.getParentItemId(), item);					
				}
				List<TreeNode<CItem>> itemTree = itemTreeUtil.serializeTree();
				
				int deleteIndex = 0;	
				int count = 0;
				CItem firstDeleteItem = null;
				
				//Delete
				for (TreeNode<CItem> node: itemTree) {
					if (node.getObject() == null) {
						continue;
					}
					
					deleteIndex++;				
					if (deleteIndex > rowCount) {
						if (count == 0) {
							firstDeleteItem = node.getObject();
						}
						count++;
						
						if (fieldActionType == 1 && !CollectionUtils.isEmpty(fieldList)) {							
							Set<CItemAttribute> itemAttributes = node.getObject().getCItemAttribute();
							Set<CItemAttribute> newItemAttributes = new LinkedHashSet<CItemAttribute>();
							if (!CollectionUtils.isEmpty(itemAttributes)) {								
								for (CItemAttribute itemAttribute: itemAttributes) {									
									if (!deletedFieldMap.containsKey(itemAttribute.getFieldId())) {
										newItemAttributes.add(itemAttribute);
									}
								}
								
								node.getObject().setCItemAttribute(newItemAttributes);
							}
						}
						
						cItemService.deleteCItem(node.getObject());
					}
				}
								
				if (count > 0) {
					cItemService.updateItemListSeq(sysId, orgId, contentId, firstDeleteItem.getListSeq(), "delete", count);
				}
			}
		}
		
		return contentId;
	}
	
	
	@Transactional
	public Integer createSelfForm(User user, 
							   	  Integer sysId, 
							   	  Integer orgId, 
							   	  Integer accessId, 
							   	  Integer parentContentId,
							   	  Integer parentRefItemId,
							   	  int rowCount, 
							   	  int columnCount) throws Exception {
		
		CItem parentItem = cItemService.getCItemByPrimaryKey(sysId, orgId, parentContentId, parentRefItemId);
		
		CForm newForm = new CForm();
		newForm.setSysId(user.getSysId());
		newForm.setOrgId(user.getOrgId());
		newForm.setContentName(parentItem.getItemName());
		newForm.setContentNumber(parentItem.getItemNumber());
		newForm.setOrgContentsId(0);
		newForm.initValue(user);
		newForm.setDescription("");
		newForm.setActionType(null);
		newForm.setParentContentId(0);
		newForm.setDepth(1);
		newForm.setHasPricingItem("N");
		newForm.setListSeq(1);
		newForm.setIsItemNameHide("N");
		newForm.setAccessId(accessId);
		newForm.setSubAccessId(accessId);
		newForm.setApplicationType(ResponseFormType.SELF.getCode());
			
		newForm = cFormService.saveCForm(newForm);
		Integer contentId = newForm.getContentsId();
		
		parentItem.setResponseFormType(ResponseFormType.SELF.getCode());
		parentItem.setAccessId(contentId);
		//cItemService.saveCItem(citem);
		
		Set<CField> fieldList = new HashSet<CField>();
		Set<CItem> existingItems = cItemService.getItemsByContentsId(sysId, orgId, contentId);

		/* **************************************************
		 * Save dynamic fields (default attribute is label)
		 ***************************************************/
		int lastSeq = cFieldService.getMaxListSeq(sysId, orgId, contentId);		
		
		// Create template Fields for WBS
		Set<CFieldTemplate> fieldTemplates = cFieldTemplateService.getCFieldTemplateByResponseFormType(sysId, orgId, ResponseFormType.SELF.getLabel());
		for (CFieldTemplate template : fieldTemplates) {
			CField newDynamicField = new CField();
			newDynamicField.setSysId(sysId);
			newDynamicField.setOrgId(orgId);
			newDynamicField.setContentsId(contentId);
			newDynamicField.setFieldName(template.getFieldName());
			newDynamicField.setDescription("");
			newDynamicField.setDefaultValue("");
			newDynamicField.setAttributeType(template.getAttributeType());
			newDynamicField.initValue(user);
			newDynamicField.setListSeq(lastSeq++);
			newDynamicField.setColumnIdentifier(StringUtil.getColIndentifier(newDynamicField.getListSeq()-1));
			newDynamicField.setIsFormula("N");
			newDynamicField.setFormula(template.getFormula());
			newDynamicField.setIsHiddenColumn("N");
			newDynamicField.setIsEditable("Y");
			newDynamicField.setIsBatchField("N");
			newDynamicField.setIsAwardee("N");
			newDynamicField.setIsTemplateField("Y");
			newDynamicField.setTemplateFieldId(template.getTemplateId());
			newDynamicField.setTarget("Buyer");
			//newDynamicField.setIsCurrency(template.getAttributeName().equals("Currency") ? "Y" : "N");
			newDynamicField.setIsCurrency(template.getIsCurrency());
			newDynamicField.setIsFormula(template.getIsFormula());
			newDynamicField.setActionType(ActionType.ADD.getCode());
			
			newDynamicField = cFieldService.saveCField(newDynamicField);
			fieldList.add(newDynamicField);
			
			cItemAttributeService.saveItemAttributeByCField(user, existingItems, newDynamicField, true);					
		}
		
		for (int i = 0; i < columnCount; i++) {			
			CField newDynamicField = new CField();
			newDynamicField.setSysId(sysId);
			newDynamicField.setOrgId(orgId);
			newDynamicField.setContentsId(contentId);
			newDynamicField.setFieldName("");
			newDynamicField.setDescription("");
			newDynamicField.setDefaultValue("");
			newDynamicField.setAttributeType("Label");
			newDynamicField.setIsCurrency("N");
			newDynamicField.initValue(user);
			newDynamicField.setListSeq(lastSeq++);
			newDynamicField.setColumnIdentifier(StringUtil.getColIndentifier(newDynamicField.getListSeq()-1));
			newDynamicField.setIsFormula("N");
			newDynamicField.setFormula(null);
			newDynamicField.setIsHiddenColumn("N");
			newDynamicField.setIsEditable("Y");
			newDynamicField.setIsBatchField("N");
			newDynamicField.setIsAwardee("N");
			newDynamicField.setIsTemplateField("N");
			newDynamicField.setTarget("Buyer");
			newDynamicField.setActionType(ActionType.ADD.getCode());
			
			newDynamicField = cFieldService.saveCField(newDynamicField);
			fieldList.add(newDynamicField);
			
			cItemAttributeService.saveItemAttributeByCField(user, existingItems, newDynamicField, true);
		}
		
		Integer maxListSeq = cItemService.getItemMaxListSeq(sysId, orgId, contentId);		
		for (int i = 0; i < rowCount; i++) {		
			CItem newItem = new CItem();
			newItem.setSysId(sysId);
			newItem.setOrgId(orgId);
			newItem.setContentsId(contentId);
			newItem.setItemNumber("");
			newItem.setItemName("");			
			newItem.initValue(user);
			newItem.setParentItemId(0);
			newItem.setDepth(1);		
			newItem.setListSeq(++maxListSeq);
			newItem.setRowIdentifier(String.valueOf(newItem.getListSeq()));
			newItem = cItemService.saveCItem(newItem);
		
			/* *********************************************
			 * Save item Attribute based on item created newly
			 ***********************************************/
			cItemAttributeService.saveItemAttributeByCItem(user, newItem, fieldList, true, null, null);
		}
		
		return contentId;
	}
	

	@Override
	public String getCommonFormFieldInfoAsJson(User user,
											   Integer cformContentId, 
											   Integer newFieldId, 
											   Integer templateId) throws Exception {

		Integer sysId = user.getSysId();
		Integer orgId = user.getOrgId();
		
		CField newField = cFieldService.getCFieldByPrimaryKey(sysId, orgId, cformContentId, newFieldId);

		List<CommonField> commonFieldList = new ArrayList<CommonField>();
		Map<Integer, List<CommonFieldAttribute>> mapCommonFieldAttribute = new HashMap<Integer, List<CommonFieldAttribute>>();
		
		/* ****************************************
		 * Generate Common Field & Field Attribute
		 ******************************************/		
		CommonField commonField = commonFormService.getField(newField);			
		commonFieldList.add(commonField);				
		
		List<CommonFieldAttribute> commonFieldAttributeBeanList = new ArrayList<CommonFieldAttribute>();
		Set<CFieldAttributes> fieldAttributes = newField.getcFieldAttributes();
		for (CFieldAttributes fieldAttribute: fieldAttributes) {
			CommonFieldAttribute commonFieldAttribute = commonFormService.getFieldAttribute(fieldAttribute);			
			commonFieldAttributeBeanList.add(commonFieldAttribute);				
		}
		
		mapCommonFieldAttribute.put(newField.getFieldId(), commonFieldAttributeBeanList);
		
		CommonFormResponse formResponse = new CommonFormResponse();
		formResponse.setCommonFieldList(commonFieldList);
		formResponse.setMapCommonFieldAttribute(mapCommonFieldAttribute);

		if (templateId > 0) {
			MasterCode materCode = masterCodeDAO.findMasterCodesByMasterCodeField(sysId, orgId, 0, MasterCodeType.UOM.getCode());
			formResponse.setMaterCode(materCode);

			Map<String, String> attributeDefaultValueMap = new HashMap<String, String>();
			Map<String, String> formulaMap = new HashMap<String, String>();
			
			Set<CItem> allItems = cItemService.getItemsByContentsId(sysId, orgId, cformContentId);
			for (CItem item : allItems) {
				Set<CItemAttribute> itemAttributes = item.getCItemAttribute();
				for (CItemAttribute itemAttr : itemAttributes) {
					formulaMap.put(itemAttr.getFormItemId() + "_" + itemAttr.getFieldId(), itemAttr.getFormula());
					attributeDefaultValueMap.put(itemAttr.getFormItemId() + "_" + itemAttr.getFieldId(), itemAttr.getDefaultValue());
				}
			}
			
			formResponse.setFormulaMap(formulaMap);
			formResponse.setAttributeDefaultValueMap(attributeDefaultValueMap);		
		}

		ObjectMapper om = new ObjectMapper();
		return om.writeValueAsString(formResponse);
	}

	@Override
	public String getCommonFormItemInfoAsJson(User user,
											  Integer cformContentId, 
											  String[] itemIds) throws Exception {
		
		Integer sysId = user.getSysId();
		Integer orgId = user.getOrgId();
		
		Set<CItem> currentItems = cItemService.getItemsByArrayOfItemIds(sysId, orgId, cformContentId, StringUtils.join(itemIds, ","));
		
		CForm contents = cFormService.getCFormByPrimaryKeyEx(sysId, orgId, cformContentId);		

		//-------------------------------------------
		// Generate Common Form
		//-------------------------------------------
		CommonForm commonForm = commonFormService.getForm(contents);
		
		Set<CField> formFields = contents.getCFields();
		
		List<CommonItem> commonItemList = new ArrayList<CommonItem>();
		Map<Integer, CommonItem> mapCommonParentItemList = new HashMap<Integer, CommonItem>();
		
		List<CommonField> commonFieldList = new ArrayList<CommonField>();
		Map<Integer, List<CommonFieldAttribute>> mapCommonFieldAttribute = new HashMap<Integer, List<CommonFieldAttribute>>();
		Map<Integer, List<CommonItemAttribute>> mapCommonItemAttribute = new HashMap<Integer, List<CommonItemAttribute>>();
		Map<Integer, List<CommonItemAttribute>> mapCommonParentItemAttribute = new HashMap<Integer, List<CommonItemAttribute>>();
		
		Map<String, String> attributeStatusMap = new HashMap<String, String>();
		Map<String, String> attributeDefaultValueMap = new HashMap<String, String>();
		Map<String, String> formulaMap = new HashMap<String, String>();
		Map<Integer, CommonStatus> biddocumentMap = new HashMap<Integer, CommonStatus>();
		
		//-------------------------------------------
		// Generate Common Field & Field Attribute
		//-------------------------------------------		
		for (CField field: formFields) {
			CommonField commonField = commonFormService.getField(field);				
			commonFieldList.add(commonField);				
			
			List<CommonFieldAttribute> commonFieldAttributeBeanList = new ArrayList<CommonFieldAttribute>();
			Set<CFieldAttributes> fieldsAttributes = field.getcFieldAttributes();
			for (CFieldAttributes fieldsAttribute: fieldsAttributes) {
				CommonFieldAttribute commonFieldAttribute = commonFormService.getFieldAttribute(fieldsAttribute);					
				commonFieldAttributeBeanList.add(commonFieldAttribute);				
			}
			
			mapCommonFieldAttribute.put(field.getFieldId(), commonFieldAttributeBeanList);
		}
		
		//-------------------------------------------
		// Generate Common Item & Item Attribute
		//-------------------------------------------
		for (CItem item : currentItems) {
			CommonItem commonItem = commonFormService.getItem(item);
			commonItemList.add(commonItem);
			
			if (item.getParentItemId() != 0) {
				if (!mapCommonParentItemList.containsKey(item.getParentItemId())) {
					CItem parentItem = cItemService.getCItemByPrimaryKey(sysId, orgId, cformContentId, item.getParentItemId());
					if (parentItem != null) {
						CommonItem commonPItem = commonFormService.getItem(parentItem);
						mapCommonParentItemList.put(parentItem.getFormItemId(), commonPItem);
						
						List<CommonItemAttribute> commonParentItemAttributeList = new ArrayList<CommonItemAttribute>();
						Set<CItemAttribute> parentItemAttributes = parentItem.getCItemAttribute();
						for (CItemAttribute itemAttr : parentItemAttributes) {
							CommonItemAttribute commonParentItemAttribute = commonFormService.getItemAttribute(itemAttr);								
							commonParentItemAttributeList.add(commonParentItemAttribute);
						}
						mapCommonParentItemAttribute.put(parentItem.getFormItemId(), commonParentItemAttributeList);
						
						if (ResponseFormType.SELF.getCode().equals(parentItem.getResponseFormType())) {
							;
						} else if (ResponseFormType.WBS.getCode().equals(parentItem.getResponseFormType())) {
							Procurement procurement = procurementDAO.findProcurementByPrimaryKey(parentItem.getAccessId(), sysId, orgId);
							CommonStatus commonStatus = new CommonStatus();
							commonStatus.setProcurementId(procurement.getProcurementId());
							commonStatus.setStageCode(procurement.getStatus().toString());
							commonStatus.setStage(ProcurementStatus.getStatus(procurement.getStatus()).getLabel());
							commonStatus.setGroupBuyingId(procurement.getGroupBuyingId());
							
							List<CommonApprovalChain> approvalChainList = new ArrayList<>();
							for (ProcurementApprovalChain approvalChain : procurement.getProcurementApprovalChains()) {
								CommonApprovalChain commonChain = new CommonApprovalChain();
								commonChain.setProcessDefId(approvalChain.getProcessDefId());
								commonChain.setProcessInstanceId(approvalChain.getPreviousInstanceId());
								commonChain.setApprovalStatus(approvalChain.getApprovalStatus());
								
								approvalChainList.add(commonChain);
							}
							
							commonStatus.setApprovalChainList(approvalChainList);;
							biddocumentMap.put(parentItem.getFormItemId(), commonStatus);
						}	
					}
				}
			}
			
			if (ResponseFormType.SELF.getCode().equals(item.getResponseFormType())) {
				;
			} else if (ResponseFormType.WBS.getCode().equals(item.getResponseFormType())) {
				Procurement procurement = procurementDAO.findProcurementByPrimaryKey(item.getAccessId(), sysId, orgId);
				CommonStatus commonStatus = new CommonStatus();
				commonStatus.setProcurementId(procurement.getProcurementId());
				commonStatus.setStageCode(procurement.getStatus().toString());
				commonStatus.setStage(ProcurementStatus.getStatus(procurement.getStatus()).getLabel());
				commonStatus.setGroupBuyingId(procurement.getGroupBuyingId());
				
				List<CommonApprovalChain> approvalChainList = new ArrayList<>();
				for (ProcurementApprovalChain approvalChain : procurement.getProcurementApprovalChains()) {
					CommonApprovalChain commonChain = new CommonApprovalChain();
					commonChain.setProcessDefId(approvalChain.getProcessDefId());
					commonChain.setProcessInstanceId(approvalChain.getPreviousInstanceId());
					commonChain.setApprovalStatus(approvalChain.getApprovalStatus());
					
					approvalChainList.add(commonChain);
				}
				
				commonStatus.setApprovalChainList(approvalChainList);;
				biddocumentMap.put(item.getFormItemId(), commonStatus);
			}	
			
			List<CommonItemAttribute> commonItemAttributeList = new ArrayList<CommonItemAttribute>();
			Set<CItemAttribute> itemAttributes = item.getCItemAttribute();
			for (CItemAttribute itemAttr : itemAttributes) {
				CommonItemAttribute commonItemAttribute = commonFormService.getItemAttribute(itemAttr);	
				commonItemAttributeList.add(commonItemAttribute);
				
				attributeStatusMap.put(itemAttr.getFormItemId() + "_" + itemAttr.getFieldId(), itemAttr.getStatus());			
				//formulaMap.put(itemAttr.getFormItemId() + "_" + itemAttr.getFieldId(), itemAttr.getFormula());
				attributeDefaultValueMap.put(itemAttr.getFormItemId() + "_" + itemAttr.getFieldId(), itemAttr.getDefaultValue());
			}
			mapCommonItemAttribute.put(item.getFormItemId(), commonItemAttributeList);
		}
		
		//-------------------------------------------
		// Find all formula attributes
		//-------------------------------------------
		//Set<CItem> allItems = cItemService.getTreeSortedCItemByContentsId(sysId, orgId, contentId);
		Set<CItem> allItems = cItemService.getItemsByContentsId(sysId, orgId, cformContentId);
		for (CItem item : allItems) {
			Set<CItemAttribute> itemAttributes = item.getCItemAttribute();
			for (CItemAttribute itemAttr : itemAttributes) {
				formulaMap.put(itemAttr.getFormItemId() + "_" + itemAttr.getFieldId(), itemAttr.getFormula());  
			}
		}
		
		MasterCode materCode = masterCodeDAO.findMasterCodesByMasterCodeField(sysId, orgId, 0, MasterCodeType.UOM.getCode());
		
		CommonFormResponse formResponse = new CommonFormResponse();
		formResponse.setCommonForm(commonForm);
		formResponse.setCommonItemList(commonItemList);
		formResponse.setMapCommonParentItemList(mapCommonParentItemList);
		formResponse.setCommonFieldList(commonFieldList);
		formResponse.setMapCommonFieldAttribute(mapCommonFieldAttribute);
		formResponse.setMapCommonItemAttribute(mapCommonItemAttribute);
		formResponse.setMapCommonParentItemAttribute(mapCommonParentItemAttribute);
		
		formResponse.setAttributeStatusMap(attributeStatusMap);
		formResponse.setAttributeDefaultValueMap(attributeDefaultValueMap);
		formResponse.setFormulaMap(formulaMap);
		formResponse.setBiddocumentMap(biddocumentMap);
		formResponse.setMaterCode(materCode);
		
		ObjectMapper om = new ObjectMapper();
		return om.writeValueAsString(formResponse);
	}

	@Override
	public String getCommonFormAllItemInfoAsJson(User user,
												 Integer cformContentId) throws Exception {
		
		Integer sysId = user.getSysId();
		Integer orgId = user.getOrgId();

		Set<CItem> currentItems = cItemService.getItemsByContentsId(sysId, orgId, cformContentId);
		
		CForm contents = cFormService.getCFormByPrimaryKeyEx(sysId, orgId, cformContentId);		

		/* ****************************************
		 * Generate Common Form
		 ******************************************/
		CommonForm commonForm = commonFormService.getForm(contents);
		
		Set<CField> formFields = contents.getCFields();
		
		List<CommonItem> commonItemList = new ArrayList<CommonItem>();
		Map<Integer, CommonItem> mapCommonParentItemList = new HashMap<Integer, CommonItem>();
		
		List<CommonField> commonFieldList = new ArrayList<CommonField>();
		Map<Integer, List<CommonFieldAttribute>> mapCommonFieldAttribute = new HashMap<Integer, List<CommonFieldAttribute>>();
		Map<Integer, List<CommonItemAttribute>> mapCommonItemAttribute = new HashMap<Integer, List<CommonItemAttribute>>();
		Map<Integer, List<CommonItemAttribute>> mapCommonParentItemAttribute = new HashMap<Integer, List<CommonItemAttribute>>();
		
		Map<String, String> attributeStatusMap = new HashMap<String, String>();
		Map<String, String> attributeDefaultValueMap = new HashMap<String, String>();
		Map<String, String> formulaMap = new HashMap<String, String>();
		Map<Integer, CommonStatus> biddocumentMap = new HashMap<Integer, CommonStatus>();
		
		/* ****************************************
		 * Generate Common Field & Field Attribute
		 ******************************************/		
		for (CField field: formFields) {
			CommonField commonField = commonFormService.getField(field);				
			commonFieldList.add(commonField);				
			
			List<CommonFieldAttribute> commonFieldAttributeBeanList = new ArrayList<CommonFieldAttribute>();
			Set<CFieldAttributes> fieldsAttributes = field.getcFieldAttributes();
			for (CFieldAttributes fieldsAttribute: fieldsAttributes) {
				CommonFieldAttribute commonFieldAttribute = commonFormService.getFieldAttribute(fieldsAttribute);					
				commonFieldAttributeBeanList.add(commonFieldAttribute);				
			}
			
			mapCommonFieldAttribute.put(field.getFieldId(), commonFieldAttributeBeanList);
		}
		
		/* ****************************************
		 * Generate Common Item & Item Attribute
		 ******************************************/
		for (CItem item : currentItems) {
			CommonItem commonItem = commonFormService.getItem(item);
			commonItemList.add(commonItem);
			
			List<CommonItemAttribute> commonItemAttributeList = new ArrayList<CommonItemAttribute>();
			Set<CItemAttribute> itemAttributes = item.getCItemAttribute();
			for (CItemAttribute itemAttr : itemAttributes) {
				CommonItemAttribute commonItemAttribute = commonFormService.getItemAttribute(itemAttr);	
				commonItemAttributeList.add(commonItemAttribute);
				
				attributeStatusMap.put(itemAttr.getFormItemId() + "_" + itemAttr.getFieldId(), itemAttr.getStatus());			
				formulaMap.put(itemAttr.getFormItemId() + "_" + itemAttr.getFieldId(), itemAttr.getFormula());
				attributeDefaultValueMap.put(itemAttr.getFormItemId() + "_" + itemAttr.getFieldId(), itemAttr.getDefaultValue());
			}
			mapCommonItemAttribute.put(item.getFormItemId(), commonItemAttributeList);
			
			if (ResponseFormType.SELF.getCode().equals(item.getResponseFormType())) {
				;
			} else if (ResponseFormType.WBS.getCode().equals(item.getResponseFormType())) {
				Procurement procurement = procurementDAO.findProcurementByPrimaryKey(item.getAccessId(), sysId, orgId);
				CommonStatus commonStatus = new CommonStatus();
				commonStatus.setProcurementId(procurement.getProcurementId());
				commonStatus.setStageCode(procurement.getStatus().toString());
				commonStatus.setStage(ProcurementStatus.getStatus(procurement.getStatus()).getLabel());
				commonStatus.setGroupBuyingId(procurement.getGroupBuyingId());
				
				List<CommonApprovalChain> approvalChainList = new ArrayList<>();
				for (ProcurementApprovalChain approvalChain : procurement.getProcurementApprovalChains()) {
					CommonApprovalChain commonChain = new CommonApprovalChain();
					commonChain.setProcessDefId(approvalChain.getProcessDefId());
					commonChain.setProcessInstanceId(approvalChain.getPreviousInstanceId());
					commonChain.setApprovalStatus(approvalChain.getApprovalStatus());
					
					approvalChainList.add(commonChain);
				}
				
				commonStatus.setApprovalChainList(approvalChainList);;
				biddocumentMap.put(item.getFormItemId(), commonStatus);
			}	
		}
		
		CommonFormResponse formResponse = new CommonFormResponse();
		formResponse.setCommonForm(commonForm);
		formResponse.setCommonItemList(commonItemList);
		formResponse.setMapCommonParentItemList(mapCommonParentItemList);
		formResponse.setCommonFieldList(commonFieldList);
		formResponse.setMapCommonFieldAttribute(mapCommonFieldAttribute);
		formResponse.setMapCommonItemAttribute(mapCommonItemAttribute);
		formResponse.setMapCommonParentItemAttribute(mapCommonParentItemAttribute);
		
		formResponse.setAttributeStatusMap(attributeStatusMap);
		formResponse.setAttributeDefaultValueMap(attributeDefaultValueMap);
		formResponse.setFormulaMap(formulaMap);
		formResponse.setBiddocumentMap(biddocumentMap);
		
		ObjectMapper om = new ObjectMapper();
		return om.writeValueAsString(formResponse);
	}

	private boolean isSubTotal(CItem item) {
		if ("Sub Total".equals(item.getItemName()) 
				&& "Sub Total".equals(item.getItemNumber())) {
			return true;
		}
		return false;
	}
	
	private String getOptionName(Integer docContentId, Integer itemOptionId) {
		Set<BidItemResOption> itemResOption = bidItemResOptionService.getBidItemResOptionByContentsIdAndItemOptionId(docContentId, itemOptionId);
		String optionName = "";
		for (BidItemResOption option : itemResOption) {
			optionName = option.getOptionName();
			break;
		}
		
		//FIXME : Make a decision which case I should access to BidDocItemOption Table later
		if (StringUtils.isEmpty(optionName)) {
			Set<BidDocItemOption> itemOption = bidDocItemOptionService.getBidDocItemOptionByContentsIdAndItemOptionId(docContentId, itemOptionId);
			for (BidDocItemOption option : itemOption) {
				optionName = option.getOptionName();
				break;
			}	
		}
		
		return optionName;
	}
	
	private String getNormalItemAttrKey(Integer suplId, Integer itemId, Integer fieldId) {
		StringBuilder sbItemAttrKey = new StringBuilder();
		sbItemAttrKey.append(suplId)
		 			 .append("_")
		 			 .append(itemId)
		 			 .append("_")
		 			 .append(fieldId);
		return sbItemAttrKey.toString();
	}
	
	private String getAlternativeStyleItemAttrKey(Integer suplId, Integer optionId, Integer itemId, Integer fieldId) {
		StringBuilder sbItemAttrKey = new StringBuilder();
		sbItemAttrKey.append(suplId)
					 .append("_")
					 .append(optionId)
		 			 .append("_")
		 			 .append(itemId)
		 			 .append("_")
		 			 .append(fieldId);
		return sbItemAttrKey.toString();
	}
	
	private String getSubTotalItemAttrKey(String itemNumber, Integer suplId, Integer itemId, Integer fieldId) {
		StringBuilder sbItemAttrKey = new StringBuilder();
		sbItemAttrKey.append(itemNumber)
	 	   			 .append("_")
	 	   			 .append(suplId)
	 	   			 .append("_")
	 	   			 .append(itemId)
	 	   			 .append("_")
	 	   			 .append(fieldId);
		return sbItemAttrKey.toString();
	}
	
	private String getFreeStyleItemAttrKey(String itemNumber, Integer itemOptionId, Integer suplId, Integer itemId, Integer fieldId) {
		StringBuilder sbItemAttrKey = new StringBuilder();
		sbItemAttrKey.append(itemNumber)
			 	   	 .append("_")
	 	   			 .append(itemOptionId)
	 	   			 .append("_")
	 	   			 .append(suplId)
	 	   			 .append("_")
	 	   			 /*.append(itemId)
	 	   			 .append("_")*/
	 	   			 .append(fieldId);
		return sbItemAttrKey.toString();
	}
	
	private String getItemOptionIdKey(Integer itemId, Integer fieldId, Integer suplId) {
		StringBuilder sbItemOptionIdKey = new StringBuilder();
		//sbItemOptionIdKey.append(jsonItemBean.getItemId())
		sbItemOptionIdKey.append(itemId)
						 .append("_")											 
						 .append(fieldId)
						 .append("_")
						 .append(suplId);
		
		return sbItemOptionIdKey.toString();
	}
	
	private FxCellData getFxCellData(String value, String formula, boolean hasChild) {
		FxCellData fxCellData = new FxCellData(value, formula);
		if (hasChild) {
			fxCellData.setDeleted(true);
		}
		return fxCellData;
	}
	
	private void generateMatrixTable(List<BidDocResponseItemBean> jsonOptionItemList, 
									 BidDocContentBean contentBean, 
									 Map<Integer, Map<Integer, Map<Integer, FxCellData>>> resultFormulaMatrixMap, 
									 TenderBidder selectedBidder) {
		Map<Integer, Map<Integer, FxCellData>> formulaMatrixMap = new HashMap<Integer, Map<Integer, FxCellData>>();
		AtomicInteger rowIdentifier = new AtomicInteger(1);
		jsonOptionItemList.stream().forEach(jsonOptionItem -> {
			Map<Integer, FxCellData> fxCellDataMap = new HashMap<Integer, FxCellData>();
			
			// Add FxCellData for 1(Item Number), 2(Item Name), 3(UOM), 4(Quantity)
			fxCellDataMap.put(1, getFxCellData("", "", jsonOptionItem.isHasChild()));
			fxCellDataMap.put(2, getFxCellData("", "", jsonOptionItem.isHasChild()));
			fxCellDataMap.put(3, getFxCellData(jsonOptionItem.getUom(), "", jsonOptionItem.isHasChild()));
			fxCellDataMap.put(4, getFxCellData(jsonOptionItem.getQuantity(), "", jsonOptionItem.isHasChild()));
			
			jsonOptionItem.getBidDocResponseFieldBeanList().stream().forEach(jsonOptionField -> {
				FxCellData fxCellData = new FxCellData(StringUtil.replaceNull(jsonOptionField.getResponseValue()), jsonOptionField.getFormula());
				if (jsonOptionItem.isHasChild()) {
					fxCellData.setDeleted(true);										
				}
				
				if (PriceDisplayFormat.PERCENTAGE.getCode().equals(jsonOptionField.getIsPercentage())) {
				    fxCellData.setPercentage(true);
				}
				
				fxCellDataMap.put(FxUtils.cm_columnCharToNumber(jsonOptionField.getColumnIdentifier(), 1), fxCellData);
			});
			formulaMatrixMap.put(rowIdentifier.getAndIncrement(), fxCellDataMap);
		});
		
		FxEvaluator.evaluate(contentBean.getContentId(), formulaMatrixMap);
		resultFormulaMatrixMap.put(selectedBidder.getBidderOrgId(), formulaMatrixMap);		
	}
	
	private void calcualteSubTotal(List<CItem> itemList, 
								   List<CField> fieldList,								    
								   Map<String, String> responseValueMap,
								   Map<String, String> subTotalFormulaMap, 
								   List<Integer> bidderOrgIdList, 
								   Map<String, BidDocContentBean> bidderContentBeanMap,
								   Map<String, Integer> itmeIdAndOptionIdMap,
								   BidDocContentBean contentBean) {
		
		Map<Integer, BigDecimal> sumFieldMap = new HashMap<Integer, BigDecimal>();
		Map<Integer, String> satrtItemPosMap = new HashMap<Integer, String>();
		
		int fieldIdx = 0;
		BigDecimal price = new BigDecimal(0);
		for (CItem item : itemList) {
			fieldIdx = 0;
			for (CField field : fieldList) {
				if (FieldAttributeType.NUMBER.getLabel().equals(field.getAttributeType())) {
					StringBuilder sbContentKey = new StringBuilder();
					sbContentKey.append(contentBean.getContentId())
						 		.append("_")
						 		.append(bidderOrgIdList.get(fieldIdx));
					
					BidDocContentBean bidderContentBean = bidderContentBeanMap.get(sbContentKey.toString());
					
					String itemOptionAttrKey;
					Integer itemOptionId = itmeIdAndOptionIdMap.get(item.getOrgItemId() + "_" + field.getOrgFieldId() + "_" + bidderOrgIdList.get(fieldIdx));
					if ("Y".equals(contentBean.getIsFreeResponseForm())) {
						if ("Y".equals(item.getIsTotalRowItem()) && "Total".equals(item.getItemNumber())) {							
							StringBuilder sbItemNumber = new StringBuilder();
							sbItemNumber.append(item.getItemNumber()).append("_").append(itemOptionId);
							itemOptionAttrKey = getFreeStyleItemAttrKey(sbItemNumber.toString(), itemOptionId, bidderOrgIdList.get(fieldIdx), item.getOrgItemId(), field.getOrgFieldId());
						} else {
							itemOptionAttrKey = getFreeStyleItemAttrKey(item.getItemNumber(), itemOptionId, bidderOrgIdList.get(fieldIdx), item.getOrgItemId(), field.getOrgFieldId());
						}
					} else if ("Y".equals(contentBean.getIsAlternativeOption()) && !"Y".equals(contentBean.getIsOptionByBuyer())) {
						itemOptionAttrKey = getAlternativeStyleItemAttrKey(bidderOrgIdList.get(fieldIdx), itemOptionId, item.getOrgItemId(), field.getOrgFieldId());
					} else {
						itemOptionAttrKey = getNormalItemAttrKey(bidderOrgIdList.get(fieldIdx), item.getOrgItemId(), field.getOrgFieldId());
					}
	
					String value = "";
					if (bidderContentBean != null 
							&& ("Y".equals(contentBean.getIsAlternativeOption()) /*&& !"Y".equals(contentBean.getIsOptionByBuyer())*/)) {
						
						StringBuilder sbBidDocItemAttrKey = new StringBuilder();
						sbBidDocItemAttrKey.append(item.getOrgItemId())
							 	 	 	   .append("_")
							 	 	 	   .append(field.getOrgFieldId());
						value = bidderContentBean.getFormulaValueMap().get(sbBidDocItemAttrKey.toString());
						if (StringUtils.isEmpty(value)) {
							value = responseValueMap.get(itemOptionAttrKey);
						}
					} else if ("Y".equals(contentBean.getIsFreeResponseForm())) {
						value = responseValueMap.get(itemOptionAttrKey);
					}
					
					if (FxUtils.cm_isNumeric(value)) {
						price = new BigDecimal(value);
					} else {
						price = new BigDecimal(0);
					}
					
					if (isSubTotal(item)) {
						BigDecimal subTotal = sumFieldMap.get(fieldIdx);
						sumFieldMap.remove(fieldIdx);
						
						String subTotalKey;
						if ("Y".equals(contentBean.getIsFreeResponseForm())) {
							subTotalKey = getSubTotalItemAttrKey(item.getItemNumber(), bidderOrgIdList.get(fieldIdx), item.getFormItemId(), field.getOrgFieldId());
						} else if ("Y".equals(contentBean.getIsAlternativeOption()) && !"Y".equals(contentBean.getIsOptionByBuyer())) {
							subTotalKey = getAlternativeStyleItemAttrKey(bidderOrgIdList.get(fieldIdx), itemOptionId, item.getFormItemId(), field.getOrgFieldId());
						} else {
							subTotalKey = getNormalItemAttrKey(bidderOrgIdList.get(fieldIdx), item.getFormItemId(), field.getOrgFieldId());
						}
	
						responseValueMap.put(subTotalKey, 
											 subTotal == null || "0".equals(subTotal.toString()) || "0.00".equals(subTotal.toString()) ? "" : subTotal.toString());
						
						if ("Y".equals(field.getIsCurrency())) {
							subTotalFormulaMap.put(subTotalKey, getSubTotalFormula(contentBean, item, satrtItemPosMap, fieldIdx));
						}
					} else {
						if (sumFieldMap.containsKey(fieldIdx)) {
							BigDecimal val = sumFieldMap.get(fieldIdx);
							sumFieldMap.put(fieldIdx, val.add(price));
						} else {
							sumFieldMap.put(fieldIdx, price);
							satrtItemPosMap.put(fieldIdx, item.getRowIdentifier());
						}
					}
				}
				fieldIdx++;
			}
		}
	}
	
	private Integer saveCItemEntities(User user, 
								   	  BidDocContentBean contentBean,
								   	  List<CItem> newitemList,
								   	  List<CItem> parentItemList,
								   	  List<CItem> cloneParentItemList,
								   	  CForm form,
								   	  Integer listSeq) throws Exception {
		//int listSeq = 1;
		//------------------------------------------------------------------------------------------
		// [Alternative option] : Create a parent item for alternative option of bid contents name
		//------------------------------------------------------------------------------------------
		if ("Y".equals(contentBean.getIsFreeResponseForm())) {
			; // No need to create a sub total item : Do nothing
		} else if ("Y".equals(contentBean.getIsAlternativeOption())/* || "Y".equals(contentBean.getIsFreeResponseForm())*/) {
			parentItemList.add(getParentItemEntity(user, form, null, listSeq++, "Bid Form"));
			parentItemList = (List<CItem>) PersistenceUtil.saveNewEntityList(entityManager, parentItemList);
			cloneParentItemList.add(parentItemList.get(0));
			newitemList.add(parentItemList.get(0));
		}
		
		//-----------------------------------
		// Save CItem Entities
		//-----------------------------------			
		int itemOptionIdx = 0;
		for (TreeNode<BidDocItemBean> itemBean : contentBean.getTreeBidDocItemBeanList()) {
			if (itemBean.getObject() == null) { continue; }
			//-----------------------------------
			// [Alternative option] :
			//-----------------------------------
			if ("Y".equals(contentBean.getIsAlternativeOption())/* || "Y".equals(contentBean.getIsFreeResponseForm())*/) {
				BidDocItemBean iBean = itemBean.getObject();
				if (iBean.getItemOptionId() != null && iBean.getItemOptionId() != 0) {
					if (itemOptionIdx != iBean.getItemOptionId()) {
						itemOptionIdx = iBean.getItemOptionId();
						
						// Create Sub Total Item
						newitemList.add(getSubTotalItemEntity(user, form, listSeq++, parentItemList.get(0).getFormItemId()));
						
						// Create Parent Item
						parentItemList.clear();							
						parentItemList.add(getParentItemEntity(user, form, itemBean.getObject(), listSeq++, getOptionName(contentBean.getContentId(), itemBean.getObject().getItemOptionId())));
						parentItemList = (List<CItem>) PersistenceUtil.saveNewEntityList(entityManager, parentItemList);
						cloneParentItemList.add(parentItemList.get(0));
						newitemList.add(parentItemList.get(0));
					}
				} 
				if (!"Y".equals(itemBean.getObject().getIsTotalRowItem())) {
					newitemList.add(getItemEntity(user, form, itemBean.getObject(), listSeq++, parentItemList.get(0).getFormItemId(), true));
				}
				
			} else {
				newitemList.add(getItemEntity(user, form, itemBean.getObject(), listSeq++, 0, true));
			}
		}
		
		// I need to check IsFreeResponseForm flag first cause there can be both options(IsFreeResponseForm(Y) and IsAlternativeOption(Y)) checked.
		if ("Y".equals(contentBean.getIsFreeResponseForm())) {
			// No need to create a sub total item : Do nothing
		} else if ("Y".equals(contentBean.getIsAlternativeOption())) {
			// Create Sub Total Item
			newitemList.add(getSubTotalItemEntity(user, form, listSeq++, parentItemList.get(0).getFormItemId()));
		}
		
		return listSeq;
	}
	
	private Integer saveCItemEntities4FreeOption(User user, 
			   								  	 BidDocContentBean contentBean,
			   								  	 CForm form,
			   								  	 List<CItem> newitemList,
			   								  	 List<CItem> parentItemList,
			   								  	 List<CItem> cloneParentItemList,			   								  
			   								  	 List<CField> newFieldList,
			   								  	 List<TenderBidder> selectedBidderList,
			   								  	 Map<String, String> responseValueMap,
			   								  	 Map<String, String> responseNoteMap,
			   								  	 Map<String, String> uomMap,
			   								  	 Map<String, String> quantityMap,
			   								  	 Map<String, String> orgFormulaExpressionMap,
			   								  	 Map<String, String> orgFormulaPositionMap,
			   								  	 Map<String, Integer> itmeIdAndOptionIdMap,
			   								  	 Map<Integer, Set<CItemAttach>> itemAttachMap,
			   								  	 Map<Integer, Map<Integer, Map<Integer, FxCellData>>> resultFormulaMatrixMap,
			   								  	 List<BigDecimal> itemOptionList,
			   								  	 Integer listSeq) throws Exception {
		
		ObjectMapper om = new ObjectMapper();
		
		// Do process for free style options
		if ("Y".equals(contentBean.getIsFreeResponseForm())) {
			// Show items by Master Codes : Within the same master codes in the same option id, 5 items for A bidder, 3 items for B bidder = total 5 items
			for (BigDecimal optionId : itemOptionList) {
				
				List<BidDocResponseItemBean> mergedOptionItemList = new ArrayList<BidDocResponseItemBean>();
				for (TenderBidder selectedBidder: selectedBidderList) {
					List<BidDocResponseItemBean> jsonOptionItemList = new ArrayList<BidDocResponseItemBean>();
					Set<JBidDocItemResponse> jsonBidDocItemResponseList = jBidDocItemResponseService.getJBidDocItemResponseByContentIdAndItemOptionIdAndSuplId(
																									contentBean.getProcurementId(),
																									contentBean.getContentId(), 
																									Integer.valueOf(optionId.toString()), 
																									selectedBidder.getBidderOrgId());
					for (JBidDocItemResponse jsonResponse : jsonBidDocItemResponseList) {
						BidDocResponseItemBean jsonItemBean = om.readValue(jsonResponse.getItemResponseJson(), BidDocResponseItemBean.class);
						jsonItemBean.setSysId(jsonResponse.getSysId());
						jsonItemBean.setOrgId(jsonResponse.getOrgId());
						jsonItemBean.setContentId(jsonResponse.getDocContentsId());
						jsonItemBean.setSuplId(jsonResponse.getSuplId());
						jsonItemBean.setItemOptionId(jsonResponse.getItemOptionId());
						
						jsonOptionItemList.add(jsonItemBean);
						mergedOptionItemList.add(jsonItemBean);
					}
					
					generateMatrixTable(jsonOptionItemList, 
							 			contentBean, 
							 			resultFormulaMatrixMap, 
							 			selectedBidder);
				}
				
				Map<String, List<BidDocResponseItemBean>> itemResponseMap = new HashMap<String, List<BidDocResponseItemBean>>();
				mergedOptionItemList
					.stream()
					.forEach(responseItemBean -> {
						try {
							List<BidDocResponseItemBean> itemFieldMap = new ArrayList<BidDocResponseItemBean>();
							responseItemBean.getBidDocResponseFieldBeanList().stream().forEach(fieldBean -> {
								Map<Integer, Map<Integer, FxCellData>> formulaMatrixMap = resultFormulaMatrixMap.get(responseItemBean.getSuplId());
								Map<Integer, FxCellData> columnMap = formulaMatrixMap.get(Integer.valueOf(responseItemBean.getRowIdentifier()));
								FxCellData fxCell = columnMap.get(FxUtils.cm_columnCharToNumber(fieldBean.getColumnIdentifier(), 1));
								// Finally set a formula result value in the response value of field bean
								fieldBean.setResponseValue(fxCell.getValue());										
								itemFieldMap.add(responseItemBean);
							});
							
							if (itemResponseMap.containsKey(responseItemBean.getItemNumber())) {
								itemResponseMap.get(responseItemBean.getItemNumber()).addAll(itemFieldMap);
							} else {
								itemResponseMap.put(responseItemBean.getItemNumber(), itemFieldMap);
							}
						} catch (Exception e) {
							throw new RuntimeException(e.getMessage());
						}
					});
				
				boolean isParentItem = true;
				boolean isTotalRow = false;
				Map<String, List<BidDocResponseItemBean>> treeItemResponseMap = new TreeMap<String, List<BidDocResponseItemBean>>(itemResponseMap);
				for (Map.Entry<String, List<BidDocResponseItemBean>> entry : treeItemResponseMap.entrySet()) {
		            List<BidDocResponseItemBean> jsonItemBeanList = entry.getValue();
		            if (jsonItemBeanList.size() > 0) {
		            	BidDocResponseItemBean jsonItemBean = jsonItemBeanList.get(0);
						if (isParentItem) {
							isParentItem = false;
							parentItemList.clear();		
							if (jsonItemBean.getItemOptionId() == null || Integer.valueOf(jsonItemBean.getItemOptionId()) == 0) {
								parentItemList.add(getParentItemEntity(user, form, jsonItemBean, listSeq++, "Bid Form"));
							} else {
								parentItemList.add(getParentItemEntity(user, form, jsonItemBean, listSeq++, getOptionName(contentBean.getContentId(), jsonItemBean.getItemOptionId())));
							}
							parentItemList = (List<CItem>) PersistenceUtil.saveNewEntityList(entityManager, parentItemList);
							cloneParentItemList.add(parentItemList.get(0));
							newitemList.add(parentItemList.get(0));
						}
						if (!"Y".equals(jsonItemBean.getIsTotalRowItem())) {
							newitemList.add(getItemEntity(user, form, jsonItemBean, listSeq, parentItemList.get(0).getFormItemId(), false));
							
				            for (BidDocResponseItemBean jItemBean : jsonItemBeanList) {
								List<BidDocResponseFieldBean> jsonFieldBeanList = jItemBean.getBidDocResponseFieldBeanList();
								for (BidDocResponseFieldBean jsonFieldBean : jsonFieldBeanList) {
									// Attr Key has a problem for Total row that cannot make an unique key with it cause it's the same name 'Total', not master format code
									String itemAttrKey = "";
									// Distinguish with "Total" and "Sub Total"
									if ("Y".equals(jItemBean.getIsTotalRowItem()) && "Total".equals(jItemBean.getItemNumber())) {
										StringBuilder sbItemNumber = new StringBuilder();
										// I cannot use itemId as a key cause certain item rows will be removed in order to generate new final item rows in CItem table.
										sbItemNumber.append(entry.getKey()).append("_").append(jItemBean.getItemOptionId());
										itemAttrKey = getFreeStyleItemAttrKey(sbItemNumber.toString(), jItemBean.getItemOptionId(), jItemBean.getSuplId(), listSeq, jsonFieldBean.getFieldId());
									} else {
										itemAttrKey = getFreeStyleItemAttrKey(entry.getKey(), jItemBean.getItemOptionId(), jItemBean.getSuplId(), listSeq, jsonFieldBean.getFieldId());
									}
									
									// When no bid item then set empty
									if (jItemBean.isNobid()) {
										responseValueMap.put(itemAttrKey, "");
									} else {
										responseValueMap.put(itemAttrKey, jsonFieldBean.getResponseValue());
									}
									responseNoteMap.put(itemAttrKey, jsonFieldBean.getNote());
									uomMap.put(itemAttrKey, jItemBean.getUom());
									quantityMap.put(itemAttrKey, jItemBean.getQuantity());	
									orgFormulaExpressionMap.put(itemAttrKey, jsonFieldBean.getFormula());
									orgFormulaPositionMap.put(itemAttrKey, jItemBean.getRowIdentifier() + "_" + jsonFieldBean.getColumnIdentifier());
									
									itmeIdAndOptionIdMap.put(getItemOptionIdKey(listSeq, jsonFieldBean.getFieldId(), jItemBean.getSuplId()), jItemBean.getItemOptionId());
								}
								
								// For Attachment by items
								setItemAttachListToMap(jItemBean, itemAttachMap, listSeq);
								
								// Map For additional UOM and Quantity data : No structure for field bean from json format
								//FIXME : if the json format has field beans for uom and quantity, I do not need below logic
								for (CField field : newFieldList) {
									if (field.getBidderId().equals(jItemBean.getSuplId())) {
										//String itemAttrKey = getFreeStyleItemAttrKey(entry.getKey(), jItemBean.getItemOptionId(), jItemBean.getSuplId(), jItemBean.getItemId(), field.getFieldId());
										String itemAttrKey = getFreeStyleItemAttrKey(entry.getKey(), jItemBean.getItemOptionId(), jItemBean.getSuplId(), listSeq, field.getFieldId());
										if ("UOM".equals(field.getFieldName())
												&& "UOM_EX".equals(field.getDescription())) {
											uomMap.put(itemAttrKey, jItemBean.getUom());
											itmeIdAndOptionIdMap.put(getItemOptionIdKey(listSeq, field.getOrgFieldId(), jItemBean.getSuplId()), jItemBean.getItemOptionId());
										} 
										if ("Quantity".equals(field.getFieldName())
												&& "Quantity_EX".equals(field.getDescription())) {
											quantityMap.put(itemAttrKey, jItemBean.getQuantity());
											itmeIdAndOptionIdMap.put(getItemOptionIdKey(listSeq, field.getOrgFieldId(), jItemBean.getSuplId()), jItemBean.getItemOptionId());
										}
									}
								}
							}
						} else {
							// We do not show a total row from eResponse system so sequence index must be reduced.
							listSeq--;
						}
		            }

		            listSeq++;
		        }
				if (!isTotalRow && mergedOptionItemList.size() > 0) {
					// Create Sub Total Item
					newitemList.add(getSubTotalItemEntity(user, form, listSeq++, parentItemList.get(0).getFormItemId()));
				}
			}
		}
		
		return listSeq;
	}
	
	private Integer saveCItemEntities4AlternativeOption(User user, 
				  									 	BidDocContentBean contentBean,
				  									 	CForm form,
				  									 	List<CItem> newitemList,
				  									 	List<CItem> parentItemList,
				  									 	List<CItem> cloneParentItemList,				  									 
				  									 	List<TenderBidder> selectedBidderList,
				  									 	Map<String, String> responseValueMap,
				  									 	Map<String, String> responseNoteMap,
				  									 	Map<String, String> uomMap,
				  									 	Map<String, String> quantityMap,
				  									 	Map<String, String> orgFormulaExpressionMap,
				  									 	Map<String, String> orgFormulaPositionMap,
				  									 	Map<Integer, Map<Integer, Map<Integer, FxCellData>>> resultFormulaMatrixMap,	
				  									 	Map<Integer, Set<CItemAttach>> itemAttachMap,
				  									 	List<BigDecimal> itemOptionList,
				  									 	Map<String, Integer> itmeIdAndOptionIdMap,
				  									 	Integer listSeq) throws Exception {
		ObjectMapper om = new ObjectMapper();
		for (BigDecimal optionId : itemOptionList) {
			for (TenderBidder selectedBidder: selectedBidderList) {
				List<BidDocResponseItemBean> jsonOptionItemList = new ArrayList<BidDocResponseItemBean>();
				Set<JBidDocItemResponse> jsonBidDocItemResponseList = jBidDocItemResponseService.getJBidDocItemResponseByContentIdAndItemOptionIdAndSuplId(
																								contentBean.getProcurementId(),
																								contentBean.getContentId(), 
																								Integer.valueOf(optionId.toString()), 
																								selectedBidder.getBidderOrgId());
				for (JBidDocItemResponse jsonResponse : jsonBidDocItemResponseList) {
					BidDocResponseItemBean jsonItemBean = om.readValue(jsonResponse.getItemResponseJson(), BidDocResponseItemBean.class);
					jsonItemBean.setSysId(jsonResponse.getSysId());
					jsonItemBean.setOrgId(jsonResponse.getOrgId());
					jsonItemBean.setContentId(jsonResponse.getDocContentsId());
					jsonItemBean.setSuplId(jsonResponse.getSuplId());
					jsonItemBean.setItemOptionId(jsonResponse.getItemOptionId());
					
					jsonOptionItemList.add(jsonItemBean);
				}
				
				generateMatrixTable(jsonOptionItemList, 
						 			contentBean, 
						 			resultFormulaMatrixMap, 
						 			selectedBidder);
				
				jsonOptionItemList
					.parallelStream()
					.forEach(responseItemBean -> {
						try {
							responseItemBean.getBidDocResponseFieldBeanList().stream().forEach(fieldBean -> {
								Map<Integer, Map<Integer, FxCellData>> formulaMatrixMap = resultFormulaMatrixMap.get(responseItemBean.getSuplId());
								Map<Integer, FxCellData> columnMap = formulaMatrixMap.get(Integer.valueOf(responseItemBean.getRowIdentifier()));
								FxCellData fxCell = columnMap.get(FxUtils.cm_columnCharToNumber(fieldBean.getColumnIdentifier(), 1));
								// Finally set a formula result value in the response value of field bean
								fieldBean.setResponseValue(fxCell.getValue());										
							});
						} catch (Exception e) {
							throw new RuntimeException(e.getMessage());
						}
					});
			
				boolean isParentItem = true;
				boolean isTotalRow = false;
				for (BidDocResponseItemBean jsonItemBean : jsonOptionItemList) {
					if (isParentItem) {
						isParentItem = false;
						
						parentItemList.clear();		
						if ("Y".equals(contentBean.getIsFreeResponseForm()) && jsonItemBean.getItemOptionId() == 0) {
							parentItemList.add(getParentItemEntity(user, form, jsonItemBean, listSeq++, "Bid Form"));
						} else {
							parentItemList.add(getParentItemEntity(user, form, jsonItemBean, listSeq++, getOptionName(contentBean.getContentId(), jsonItemBean.getItemOptionId())));
						}
						parentItemList = (List<CItem>) PersistenceUtil.saveNewEntityList(entityManager, parentItemList);
						cloneParentItemList.add(parentItemList.get(0));
						newitemList.add(parentItemList.get(0));
					}
					
					if (!"Y".equals(jsonItemBean.getIsTotalRowItem())) {
						newitemList.add(getItemEntity(user, form, jsonItemBean, listSeq, parentItemList.get(0).getFormItemId(), false));
						
						List<BidDocResponseFieldBean> jsonFieldBeanList = jsonItemBean.getBidDocResponseFieldBeanList();
						for (BidDocResponseFieldBean jsonFieldBean : jsonFieldBeanList) {
							String itemAttrKey = getAlternativeStyleItemAttrKey(jsonItemBean.getSuplId(), jsonItemBean.getItemOptionId(), listSeq, jsonFieldBean.getFieldId());
							
							// When no bid item then set empty
							if (jsonItemBean.isNobid()) {
								responseValueMap.put(itemAttrKey, "");
							} else {
								responseValueMap.put(itemAttrKey, jsonFieldBean.getResponseValue());
							}
							responseNoteMap.put(itemAttrKey, jsonFieldBean.getNote());
							uomMap.put(itemAttrKey, jsonItemBean.getUom());
							quantityMap.put(itemAttrKey, jsonItemBean.getQuantity());
							orgFormulaExpressionMap.put(itemAttrKey, jsonFieldBean.getFormula());
							orgFormulaPositionMap.put(itemAttrKey, jsonItemBean.getRowIdentifier() + "_" + jsonFieldBean.getColumnIdentifier());
							
							itmeIdAndOptionIdMap.put(getItemOptionIdKey(listSeq, jsonFieldBean.getFieldId(), jsonItemBean.getSuplId()), jsonItemBean.getItemOptionId());
						}

						// For Attachment by items
						setItemAttachListToMap(jsonItemBean, itemAttachMap, listSeq);
					} else {
						// We do not show a total row from eResponse system so sequence index must be reduced.
						listSeq--;
					}
					
					listSeq++;
				}
				if (!isTotalRow && jsonBidDocItemResponseList.size() > 0) {
					// Create Sub Total Item
					newitemList.add(getSubTotalItemEntity(user, form, listSeq++, parentItemList.get(0).getFormItemId()));
				}
			}
		}
		
		return listSeq;
	}
	
	private void saveCFieldEntities(User user, 
									BidDocContentBean contentBean, 
									CForm form, 
									List<TenderBidder> selectedBidderList, 
									List<CField> newFieldList, 
									List<Integer> bidderOrgIdList) throws Exception {
		int listSeq = 1;
		for (TenderBidder selectedBidder: selectedBidderList) {
			int bidderListSeq = 1;
			int fieldPos = 0;
			int tempFieldId = 0;
			for (BidDocFieldBean fieldBean : contentBean.getBidDocFieldBeanList()) {
				if ("Y".equals(contentBean.getIsFreeResponseForm())) {
					if (fieldPos == 0) {
						newFieldList.add(getExtraFieldEntity(user, form, selectedBidder.getBidderName(), tempFieldId++, selectedBidder.getBidderOrgId(), listSeq, bidderListSeq++, "UOM"));
						bidderOrgIdList.add(selectedBidder.getBidderOrgId());
						newFieldList.add(getExtraFieldEntity(user, form, selectedBidder.getBidderName(), tempFieldId, selectedBidder.getBidderOrgId(), listSeq, bidderListSeq++, "Quantity"));
						bidderOrgIdList.add(selectedBidder.getBidderOrgId());
						fieldPos++;
					}
					newFieldList.add(getFieldEntity(user, form, fieldBean, selectedBidder.getBidderName(), selectedBidder.getBidderOrgId(), listSeq++, bidderListSeq++));
				} else {
					newFieldList.add(getFieldEntity(user, form, fieldBean, selectedBidder.getBidderName(), selectedBidder.getBidderOrgId(), listSeq++, bidderListSeq++));
				}
				bidderOrgIdList.add(selectedBidder.getBidderOrgId());
			}
		}
		newFieldList = (List<CField>) PersistenceUtil.saveNewEntityList(entityManager, newFieldList);
	}
	
	private void saveCItemAttrEntities(User user, 
									   BidDocContentBean contentBean,
									   Map<String, BidDocContentBean> bidderContentBeanMap,
									   CForm form, 
									   List<CItem> newitemList, 
									   List<CField> newFieldList, 
									   List<Integer> bidderOrgIdList,
									   Map<String, String> responseValueMap,
									   Map<String, String> responseNoteMap,
									   Map<String, String> subTotalFormulaMap,
									   Map<String, String> uomMap,
									   Map<String, String> quantityMap,
									   Map<String, String> orgFormulaExpressionMap,
									   Map<String, String> orgFormulaPositionMap,
									   Map<String, Integer> itmeIdAndOptionIdMap) throws Exception {
		
		int fieldIdx = 0;
		List<CItemAttribute> newItemAttrList = new ArrayList<>();
		
		for (CItem item : newitemList) {
			fieldIdx = 0;
			for (CField field : newFieldList) {				
				StringBuilder sbContentKey = new StringBuilder();
				sbContentKey.append(contentBean.getContentId())
					 		.append("_")
					 		.append(bidderOrgIdList.get(fieldIdx));
				
				BidDocContentBean bidderContentBean = bidderContentBeanMap.get(sbContentKey.toString());
				if (bidderContentBean == null) continue;
				
				StringBuilder sbKey = new StringBuilder();
				sbKey.append(item.getOrgItemId())
					 .append("_")
					 .append(field.getOrgFieldId());	
				if (bidderContentBean.getFormulaValueMap().containsKey(sbKey.toString()) 
						|| ("Y".equals(bidderContentBean.getIsAlternativeOption()) && "Y".equals(bidderContentBean.getIsOptionByBuyer())) ) {
					if (bidderContentBean != null) {
						//FIXME : Needs to add a flag for indicating weather it is sub total or not later
						if (isSubTotal(item)) {
							String subTotalKey = getNormalItemAttrKey(bidderOrgIdList.get(fieldIdx), item.getFormItemId(), field.getOrgFieldId());
							newItemAttrList.add(getItemAttributeEntity(user, form, item, field, bidderContentBean, responseValueMap.get(subTotalKey), null, subTotalFormulaMap.get(subTotalKey)));
						} else {
							String newFormula = convertOrgFormulaToNewFormula(bidderContentBean, 
																			  field,
																			  bidderContentBean.getFormulaMap().get(sbKey.toString()), 
																			  bidderContentBean.getOrgFormulaPositionMap().get(sbKey.toString()),
																			  Integer.valueOf(item.getRowIdentifier()),
									  										  fieldIdx);
							newItemAttrList.add(getItemAttributeEntity(user, form, item, field, bidderContentBean, null, null, newFormula));
						}
					}
				} else if (("Y".equals(bidderContentBean.getIsAlternativeOption()) && !"Y".equals(bidderContentBean.getIsOptionByBuyer())) 
						|| "Y".equals(bidderContentBean.getIsFreeResponseForm())) {
					
					if (isSubTotal(item)) {
						String itemAttrKey;
						if ("Y".equals(bidderContentBean.getIsFreeResponseForm())) {
							itemAttrKey = getSubTotalItemAttrKey(item.getItemNumber(), bidderOrgIdList.get(fieldIdx), item.getFormItemId(), field.getOrgFieldId());
						} else if ("Y".equals(bidderContentBean.getIsAlternativeOption()) && !"Y".equals(contentBean.getIsOptionByBuyer())) {
							Integer itemOptionId = itmeIdAndOptionIdMap.get(item.getOrgItemId() + "_" + field.getOrgFieldId() + "_" + bidderOrgIdList.get(fieldIdx));
							itemAttrKey = getAlternativeStyleItemAttrKey(bidderOrgIdList.get(fieldIdx), itemOptionId, item.getFormItemId(), field.getOrgFieldId());
						} else {
							itemAttrKey = getNormalItemAttrKey(bidderOrgIdList.get(fieldIdx), item.getFormItemId(), field.getOrgFieldId());
						}
						newItemAttrList.add(getItemAttributeEntity(user, form, item, field, bidderContentBean, responseValueMap.get(itemAttrKey), responseNoteMap.get(itemAttrKey), subTotalFormulaMap.get(itemAttrKey)));
					} else {
						String itemAttrKey;
						Integer itemOptionId = itmeIdAndOptionIdMap.get(item.getOrgItemId() + "_" + field.getOrgFieldId() + "_" + bidderOrgIdList.get(fieldIdx));
						if ("Y".equals(bidderContentBean.getIsFreeResponseForm())) {
							if ("Y".equals(item.getIsTotalRowItem()) && "Total".equals(item.getItemNumber())) {
								StringBuilder sbItemNumber = new StringBuilder();
								sbItemNumber.append(item.getItemNumber());
								itemAttrKey = getFreeStyleItemAttrKey(sbItemNumber.toString(), itemOptionId, bidderOrgIdList.get(fieldIdx), item.getOrgItemId(), field.getOrgFieldId());
							} else {
								itemAttrKey = getFreeStyleItemAttrKey(item.getItemNumber(), itemOptionId, bidderOrgIdList.get(fieldIdx), item.getOrgItemId(), field.getOrgFieldId());
							}
						} else if ("Y".equals(bidderContentBean.getIsAlternativeOption()) && !"Y".equals(contentBean.getIsOptionByBuyer())) {
							itemAttrKey = getAlternativeStyleItemAttrKey(bidderOrgIdList.get(fieldIdx), itemOptionId, item.getOrgItemId(), field.getOrgFieldId());
						} else {
							itemAttrKey = getNormalItemAttrKey(bidderOrgIdList.get(fieldIdx), item.getOrgItemId(), field.getOrgFieldId());
						}
						
						//FIXME : if the json format has field beans for uom and quantity, I do not need below logic
						if ("UOM".equals(field.getFieldName())
								&& "UOM_EX".equals(field.getDescription())) {
							itemAttrKey = getFreeStyleItemAttrKey(item.getItemNumber(), itemOptionId, bidderOrgIdList.get(fieldIdx), item.getOrgItemId(), field.getFieldId());
							newItemAttrList.add(getItemAttributeEntity(user, form, item, field, bidderContentBean, uomMap.get(itemAttrKey), responseNoteMap.get(itemAttrKey), null));
						} else if ("Quantity".equals(field.getFieldName())
								&& "Quantity_EX".equals(field.getDescription())) {
							itemAttrKey = getFreeStyleItemAttrKey(item.getItemNumber(), itemOptionId, bidderOrgIdList.get(fieldIdx), item.getOrgItemId(), field.getFieldId());
							newItemAttrList.add(getItemAttributeEntity(user, form, item, field, bidderContentBean, quantityMap.get(itemAttrKey), responseNoteMap.get(itemAttrKey), null));
						} else {
							String newFormula = convertOrgFormulaToNewFormula(bidderContentBean, 
																			  field,
															  				  orgFormulaExpressionMap.get(itemAttrKey), 
															  				  orgFormulaPositionMap.get(itemAttrKey),
															  				  Integer.valueOf(item.getRowIdentifier()),
															  				  fieldIdx);
							newItemAttrList.add(getItemAttributeEntity(user, form, item, field, bidderContentBean, responseValueMap.get(itemAttrKey), responseNoteMap.get(itemAttrKey), newFormula));
						}
					}
				}
				fieldIdx++;
			}
		}
		
		PersistenceUtil.saveNewEntityList(entityManager, newItemAttrList);
		
		//------------------------------------------------------------
		// Convert existing batch formula to new formula in field
		//------------------------------------------------------------
		fieldIdx = 0;
		for (CField field : newFieldList) {
			StringBuilder sbContentKey = new StringBuilder();
			sbContentKey.append(contentBean.getContentId())
				 		.append("_")
				 		.append(bidderOrgIdList.get(fieldIdx));
			
			BidDocContentBean bidderContentBean = bidderContentBeanMap.get(sbContentKey.toString());
			if (bidderContentBean == null) continue;
			
			if (!StringUtils.isEmpty(field.getFormula())) {
				String tmpFormula = FxUtils.batchFormula(field.getFormula(), "1");
				int jumpToNewColPos = (fieldIdx + 1) - field.getBidderListseq();
				String newFormula = null;
				if (jumpToNewColPos > 0) {
					newFormula = FxUtils.cm_adjustIdentifier(tmpFormula, BidDocUtil.getFrontFixedColumnCount(bidderContentBean), jumpToNewColPos, false);
				} else {
					newFormula = tmpFormula;
				}
				field.setFormula(FxUtils.convertToBatchFormula(newFormula));
			}
			fieldIdx++;
		}
		
		PersistenceUtil.updateEntityList(entityManager, newFieldList);
	}
	
	private void adjustItemTreeHierarchy(List<CItem> newitemList) throws Exception {
		Map<Integer, Integer> newItemId_OrgParentItemId_Map = new HashMap<Integer, Integer>();
		Map<Integer, Integer> orgItemId_NewItemId_Map = new HashMap<Integer, Integer>();
		
		newitemList.parallelStream().forEach(item -> {
			newItemId_OrgParentItemId_Map.put(item.getFormItemId(), item.getOrgParentItemId());
			orgItemId_NewItemId_Map.put(item.getOrgItemId(), item.getFormItemId());
		});
		newitemList.parallelStream().forEach(item -> {
			Integer orgParentItemId = newItemId_OrgParentItemId_Map.get(item.getFormItemId());
			if (orgParentItemId != null && orgParentItemId != 0) {
				item.setParentItemId(orgItemId_NewItemId_Map.get(orgParentItemId));
			}
		});
		
		newitemList = (List<CItem>) PersistenceUtil.updateEntityList(entityManager, newitemList);
	}
	
	private void saveCItemAttachEntities(List<CItem> newItemList,
										 List<CField> fieldList,								    
										 List<Integer> bidderOrgIdList, 
										 Map<Integer, Set<CItemAttach>> itemAttachMap, 
										 Map<String, BidDocContentBean> bidderContentBeanMap,
										 BidDocContentBean contentBean) throws Exception {
		
		Map<Integer, Set<CItemAttach>> finalItemAttachMap = new HashMap<Integer, Set<CItemAttach>>();
		Set<CItemAttach> finalItemAttachList = new HashSet<CItemAttach>();

		int fieldIdx = 0;
		for (CField field : fieldList) {
			StringBuilder sbContentKey = new StringBuilder();
			sbContentKey.append(contentBean.getContentId())
				 		.append("_")
				 		.append(bidderOrgIdList.get(fieldIdx++));
			
			BidDocContentBean bidderContentBean = bidderContentBeanMap.get(sbContentKey.toString());
			Map<Integer, Set<CItemAttach>> bidResponseItemAttachMap = bidderContentBean != null ? bidderContentBean.getItemAttachMap() : new HashMap<Integer, Set<CItemAttach>>();
			
			newItemList.parallelStream().forEach(item -> {
				if (itemAttachMap.containsKey(item.getOrgItemId())) {
					Set<CItemAttach> itemAttachs = itemAttachMap.get(item.getOrgItemId());
					itemAttachs.parallelStream().forEach(attach -> {
						attach.setContentsId(item.getContentsId());
						attach.setFormItemId(item.getFormItemId());
					});
				} else if (bidResponseItemAttachMap.containsKey(item.getOrgItemId())) {
					Set<CItemAttach> itemAttachs = bidResponseItemAttachMap.get(item.getOrgItemId());
					itemAttachs.parallelStream().forEach(attach -> {
						attach.setContentsId(item.getContentsId());
						attach.setFormItemId(item.getFormItemId());
					});
				}
			});
			
			finalItemAttachMap.putAll(itemAttachMap);
			finalItemAttachMap.putAll(bidResponseItemAttachMap);
			for (Map.Entry<Integer, Set<CItemAttach>> entry : finalItemAttachMap.entrySet()) {
				finalItemAttachList.addAll(entry.getValue());
			}
		}
		
		PersistenceUtil.saveNewEntityList(entityManager, finalItemAttachList);
	}
	
	private String getSubTotalFormula(BidDocContentBean contentBean, CItem item, Map<Integer, String> satrtItemPosMap, int fieldIdx) {
		
		int colIdx = 0;
		if ("Y".equals(contentBean.getIsFreeResponseForm())) {
			if ("Y".equals(contentBean.getIsItemNameHide())) {
				colIdx = fieldIdx + 2;
			} else {
				colIdx = fieldIdx + 3;
			}
		} else if ("Y".equals(contentBean.getIsAlternativeOption()) && "Y".equals(contentBean.getIsOptionByBuyer())) {
			if ("Y".equals(contentBean.getIsItemNameHide())) {
				colIdx = fieldIdx + 4;
			} else {
				colIdx = fieldIdx + 5;
			}
		} else if (!"Y".equals(contentBean.getIsAlternativeOption())) {
			if ("Y".equals(contentBean.getIsItemNameHide())) {
				colIdx = fieldIdx + 4;
			} else {
				colIdx = fieldIdx + 5;
			}
		} else {
			if ("Y".equals(contentBean.getIsItemNameHide())) {
				colIdx = fieldIdx + 4;
			} else {
				colIdx = fieldIdx + 5;
			}
		}
		
		String colIdentifier = FxUtils.cm_columnNumberToChar(colIdx, 1);
		String fromRowIdentifier = satrtItemPosMap.get(fieldIdx);
		String toRowIdentifier = String.valueOf(Integer.valueOf(item.getRowIdentifier()) - 1);
		
		StringBuilder sbSubTotalFormula = new StringBuilder();
		sbSubTotalFormula.append("=SUM(")
						 .append(colIdentifier).append(fromRowIdentifier)
						 .append(":")
						 .append(colIdentifier).append(toRowIdentifier)
						 .append(")");
		return sbSubTotalFormula.toString();
	}
	
	private int getFrontColHeaderCount(BidDocContentBean contentBean) {
		if ("Y".equals(contentBean.getIsFreeResponseForm())) {
			if ("Y".equals(contentBean.getIsItemNameHide())) {
				return 1;
			} else {
				return 2;
			}
		} else {
			if ("Y".equals(contentBean.getIsItemNameHide())) {
				return 3;
			} else {
				return 4;
			}
		}
	}
	
	private String convertOrgFormulaToNewFormula(BidDocContentBean bidderContentBean,
												 CField field,
								 			   	 String orgFormula,
								 			   	 String orgFormulaPos,
								 			   	 int curItemRow,
								 			   	 int curFieldIdx) throws Exception {
		String newFormula = null;
		if (!StringUtils.isEmpty(orgFormula)) {
			String[] posArray = orgFormulaPos.split("_");	
			if (posArray.length == 2) {
				int orgRowPos = Integer.valueOf(posArray[0]);
				int orgColPos = FxUtils.cm_columnCharToNumber(posArray[1], 1);
				int jumpToNewRowPos = curItemRow - orgRowPos;
				int jumpToNewColPos = BidDocUtil.getFrontFixedColumnCount(bidderContentBean) + (curFieldIdx + 1) - orgColPos;
				if (jumpToNewColPos > 0) {
					newFormula = FxUtils.cm_adjustIdentifier(orgFormula, BidDocUtil.getFrontFixedColumnCount(bidderContentBean), jumpToNewColPos, false);
				} else {
					newFormula = orgFormula;
				}
				
				//if (jumpToNewRowPos > 0) {
					newFormula = FxUtils.cm_adjustIdentifier(newFormula, 0, jumpToNewRowPos, true);
				//}
			}
		} else {
			if ("Y".equals(field.getIsFormula())) {
				String batchFormula = field.getFormula();
				if (!StringUtils.isEmpty(batchFormula)) {
					String tmpFormula = FxUtils.batchFormula(batchFormula, String.valueOf(curItemRow));
					int jumpToNewColPos = (curFieldIdx + 1) - field.getBidderListseq();
					if (jumpToNewColPos > 0) {
						newFormula = FxUtils.cm_adjustIdentifier(tmpFormula, BidDocUtil.getFrontFixedColumnCount(bidderContentBean), jumpToNewColPos, false);
					} else {
						newFormula = tmpFormula;
					}
				}
			}
		}
		
		return newFormula;
	}
	
	private void setItemAttachListToMap(BidDocResponseItemBean item, Map<Integer, Set<CItemAttach>> itemAttachMap, Integer listSeq) {
		Set<CItemAttach> itemAttachs = new HashSet<CItemAttach>();
		
		item.getBidDocResponseAttachmentList().stream().forEach(attach -> {
			CItemAttach itemAttach = new CItemAttach();
			itemAttach.setSysId(item.getSysId());
			itemAttach.setOrgId(item.getOrgId());
			itemAttach.setContentsId(item.getContentId());
			itemAttach.setFormItemId(item.getItemId());
			itemAttach.setAttachId(attach.getAttachId());
			itemAttach.setFileName(attach.getFileName());
			itemAttach.setEncodedFileName(attach.getEncodedFileName());
			itemAttach.setFileMimeType(attach.getFileMimeType());
			itemAttach.setFileSize(attach.getFileSize());
			itemAttach.setFilePath(attach.getFilePath());
			itemAttach.setDescription(attach.getDescription());
			itemAttach.setSuplId(attach.getSuplId());						
			itemAttachs.add(itemAttach);
		});
		itemAttachMap.put(listSeq, itemAttachs);
	}
	
	@Transactional
    public Map<Integer, String> cloneAllEntitiesAsscoiatedWithCForm(User user, Integer procurementId, Integer pricingFormContentId) throws Exception {
	    Map<Integer, String> newContentInfoMap = new HashMap<Integer, String>();
	    
        CForm  cForm = cFormService.getCFormByAccessIdAndContentIdEx(user.getSysId(), user.getOrgId(), procurementId, pricingFormContentId);
        Set<CItem> cItems   = cForm.getCItems();
        Set<CField> cFields = cForm.getCFields();
        
        List<CForm> cForms                      = new ArrayList<>();
        List<CItemAttribute> cItemAttributes    = new ArrayList<>();
        List<CItemAttach> cItemAttachs          = new ArrayList<>();
        List<CFieldAttributes> cFieldAttibutes  = new ArrayList<>();
        
        Map<Integer, Set<CItemAttach>> cItemAttachMap                  = new HashMap<>();
        Map<Integer, Map<Integer, CItemAttribute>> cItemAttributeMap   = new HashMap<>();
        Map<Integer, Set<CFieldAttributes>> cFieldAttributeMap         = new HashMap<>();
        
        cItems.stream().forEach(item -> { 
            cItemAttachMap.put(item.getFormItemId(), item.getCItemAttach());
            Map<Integer, CItemAttribute> attrMap = new HashMap<>();
            item.getCItemAttribute().stream().forEach(attr -> {
                attrMap.put(attr.getFieldId(), attr);
            });
            cItemAttributeMap.put(item.getFormItemId(), attrMap); 
        });
        cFields.stream().forEach(field -> { cFieldAttributeMap.put(field.getFieldId(), field.getcFieldAttributes()); });
        
        //-------------------------------------
        // New CForm
        //-------------------------------------
        entityManager.detach(cForm);
        StringBuilder sbNewContentName = new StringBuilder();
        sbNewContentName.append(cForm.getContentName())
                        .append(" - Copy");
        cForm.setContentName(sbNewContentName.toString());
        cForm.setCopiedFromContentsId(cForm.getCopiedFromContentsId() == null ? cForm.getContentsId() : cForm.getCopiedFromContentsId());
        cForm.setIsSelcetedEvalContent("N");
        cForm.initValue(user);
        cForm.setContentsId(0);
        cForms.add(cForm);        
        List<CForm> newCForms = (List<CForm>) PersistenceUtil.saveNewEntityList(entityManager, cForms);
        
        if (newCForms.size() > 0) {
            CForm newCForm = newCForms.iterator().next();
            //-------------------------------------
            // New CItem
            //-------------------------------------            
            cItems.stream().forEach(item -> { 
                entityManager.detach(item);
                item.setContentsId(newCForm.getContentsId());
                item.setOrgItemId(item.getFormItemId());
                item.setOrgParentItemId(item.getParentItemId());
                item.setCForm(null);
                item.setChildCItem(null);
                item.setChildItemses(null);
                item.setEvalAwardeeItems(null);
                item.initValue(user);
                item.setFormItemId(0);
            });
            List<CItem> newCItems = (List<CItem>) PersistenceUtil.saveNewEntityList(entityManager, new ArrayList<>(cItems));
            
            //-------------------------------------            
            // New CItemAttach
            //-------------------------------------            
            newCItems.stream().forEach(newCItem -> {
                cItemAttachMap.get(newCItem.getOrgItemId()).stream().forEach(itemAttach -> {
                    entityManager.detach(itemAttach);
                    itemAttach.setContentsId(newCForm.getContentsId());
                    itemAttach.setFormItemId(newCItem.getFormItemId());
                    itemAttach.setCItem(null);
                    cItemAttachs.add(itemAttach);
                });
            });
            PersistenceUtil.saveNewEntityList(entityManager, cItemAttachs);
            
            //-------------------------------------            
            // New CField
            //-------------------------------------            
            cFields.stream().forEach(field -> {
                entityManager.detach(field);
                field.setContentsId(newCForm.getContentsId());
                field.setOrgFieldId(field.getFieldId());
                field.setCForm(null);
                field.initValue(user);
                field.setFieldId(0);
            });
            List<CField> newCFields = (List<CField>) PersistenceUtil.saveNewEntityList(entityManager, new ArrayList<>(cFields));
            
            //-------------------------------------            
            // New CFieldAttribute
            //-------------------------------------            
            newCFields.stream().forEach(newCField -> {
                cFieldAttributeMap.get(newCField.getOrgFieldId()).stream().forEach(fieldAttr -> {
                    entityManager.detach(fieldAttr);
                    fieldAttr.setContentsId(newCForm.getContentsId());
                    fieldAttr.setFieldId(newCField.getFieldId());
                    fieldAttr.setCField(null);
                    fieldAttr.setAttributeId(0);
                    cFieldAttibutes.add(fieldAttr);
                });
            });
            PersistenceUtil.saveNewEntityList(entityManager, cFieldAttibutes);
            
            //-------------------------------------            
            // New CItemAttribute
            //-------------------------------------            
            newCItems.stream().forEach(newCItem -> {
                Map<Integer, CItemAttribute> attrMap = cItemAttributeMap.get(newCItem.getOrgItemId());
                newCFields.stream().forEach(newCField -> {
                    CItemAttribute itemAttr = attrMap.get(newCField.getOrgFieldId());
                    entityManager.detach(itemAttr);
                    itemAttr.setContentsId(newCForm.getContentsId());
                    itemAttr.setFormItemId(newCItem.getFormItemId());
                    itemAttr.setFieldId(newCField.getFieldId());
                    itemAttr.setCField(null);
                    itemAttr.setCForm(null);
                    itemAttr.setCItem(null);
                    itemAttr.initValue(user);
                    itemAttr.setAttrId(0);
                    cItemAttributes.add(itemAttr);
                });
            });
            PersistenceUtil.saveNewEntityList(entityManager, cItemAttributes);
            
            adjustItemTreeHierarchy(newCItems);
            
            newContentInfoMap.put(newCForm.getContentsId(), newCForm.getContentName());
            return newContentInfoMap;
        }
        
        return newContentInfoMap;
    }
	
	@Transactional
    public void removeAllEntitiesAsscoiatedWithCForm(User user, Integer procurementId, Integer pricingFormContentId) throws Exception {
	    CForm  cForm = cFormService.getCFormByAccessIdAndContentId(procurementId, pricingFormContentId);
	    cForm.setCFields(null);
	    cForm.setCItems(null);
	    cForm.setItemAttributes(null);
	    cForm.setItemAttributesWithFormula(null);
        cFormService.deleteCForm(cForm);
    }
	
	public String getFreeResponseInfoByContentId(User user, Integer procurementId, Integer bidDocContentId) throws Exception {
	    BidDocAddendum bidDocAddendum = bidDocAddendumDAO.findFinalPublishedBidDocAddendum(user.getSysId(), user.getOrgId(), procurementId);
	    if (bidDocAddendum != null) {
	        BidDocAddendumContent bidAddDocContent = 
	                bidDocAddendumContentDAO.findBidDocAddendumContentByPrimaryKey(user.getSysId(), user.getOrgId(), procurementId, bidDocAddendum.getAddendumId(), bidDocContentId);
	        return bidAddDocContent.getIsFreeResponseForm();
	    } else {
	        BidDocumentsContents bidDocContent = 
	                bidDocumentsContentsDAO.findBidDocumentsContentsByPrimaryKey(user.getSysId(), user.getOrgId(), procurementId, bidDocContentId);
	        return bidDocContent.getIsFreeResponseForm();
	    }
	}
	
	public Set<CForm> getCFormSetFromMainContent(Integer procurementId, Integer mainContentId) throws Exception {
		Set<CForm> cFormSet = new LinkedHashSet<>();
		cFormSet.add(cFormService.getCFormByAccessIdAndContentId(procurementId, mainContentId));
		cFormSet.addAll(cFormService.findCFormByAccessIdAndCopiedFromContentsId(procurementId, mainContentId));
		
		return cFormSet;
	}
}
