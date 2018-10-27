package com.biddingo.framework.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaDelete;
import javax.persistence.criteria.Root;

import org.hibernate.Criteria;
import org.hibernate.HibernateException;
import org.hibernate.ScrollableResults;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.criterion.Restrictions;

import com.biddingo.common.formitem.domain.CItem;
import com.biddingo.common.formitem.domain.CItemAttribute;
import com.biddingo.framework.entity.BaseEntity;
import com.biddingo.framework.formula.utils.FxUtils;
import com.biddingo.procurement.biddoc.addendum.domain.BidDocAddendumItem;
import com.biddingo.procurement.biddoc.addendum.domain.BidDocAddendumItemAttr;
import com.biddingo.procurement.biddoc.revision.domain.BidDocRevisionFormItem;
import com.biddingo.procurement.biddoc.revision.domain.BidDocRevisionItemAttr;
import com.biddingo.procurement.biddocument.domain.BidDocumentsFormItems;
import com.biddingo.procurement.biddocument.domain.BidDocumentsItemAttribute;

/*
 * By James
 * Persistence Utility for batch updating, deleting, and inserting
 */

public class PersistenceUtil {
	private static int BATCHSIZE = 50;
	
	public static <T extends Object> Collection<T> saveNewEntityList(EntityManager entityManager, Collection<T> entities) throws Exception {
		if (entities.size() > 0) {
			return saveAll(entityManager, entities);
		}
		
		return new ArrayList<T>();
	}
	
	private static <T extends Object> Collection<T> saveAll(EntityManager entityManager, Collection<T> entities) {
		final List<T> savedEntities = new ArrayList<T>(entities.size());
		int idx = 0;
		for (T t : entities) {
			if (t == null) {
				continue;
			}
			savedEntities.add(persistOrMerge(entityManager, t));
			idx++;
			if (idx % BATCHSIZE == 0) {
				entityManager.flush();
				entityManager.clear();
			}
		}
		
		entityManager.flush();
		entityManager.clear();
		return savedEntities;
	}

	private static <T extends Object> T persistOrMerge(EntityManager entityManager, T t) {
		//return entityManager.merge(t);
		entityManager.persist(t);
		return t;		
	}
	
	
	public static <T extends Object> Collection<T> updateEntityList(EntityManager entityManager, Collection<T> entities) throws Exception {
		if (entities.size() > 0) {
			return updateAll(entityManager, entities);
		}
		
		return new ArrayList<T>();
	}
	
	private static <T extends Object> Collection<T> updateAll(EntityManager entityManager, Collection<T> entities) {
		final List<T> savedEntities = new ArrayList<T>(entities.size());
		int idx = 0;
		for (T t : entities) {
			if (t == null) {
				continue;
			}
			savedEntities.add(merge(entityManager, t));
			idx++;
			if (idx % BATCHSIZE == 0) {
				entityManager.flush();
				entityManager.clear();
			}
		}
		
		entityManager.flush();
		entityManager.clear();
		return savedEntities;
	}

	private static <T extends Object> T merge(EntityManager entityManager, T t) {
		return entityManager.merge(t);
	}	

	public static <T extends Object> void updateEntityList(EntityManager entityManager, 
														   Collection<T> entities, 
														   Integer compareNum, 
														   Integer affectedFieldCount, 
														   boolean isRow ) throws Exception {
		if (entities.size() > 0) {
	        StringBuilder sbWhere 		= new StringBuilder();
	        StringBuilder sbCase 		= new StringBuilder();
	        StringBuilder sbWhen 		= new StringBuilder();
	        StringBuilder sbCondition 	= new StringBuilder();
	        
	        sbWhere.append(" WHERE (form_item_id, field_id) IN ");	        
	        sbCase.append(" CASE ");
	        sbCondition.append("(");
	        
	        Integer formItemId = 0;
	        Integer fieldId = 0;
	        String formula = "";
	        String tableName = "";
	        
			for (T itemAttr : entities) {
				if (itemAttr instanceof BidDocAddendumItemAttr) {
					formItemId 	= ((BidDocAddendumItemAttr) itemAttr).getFormItemId();
					fieldId 	= ((BidDocAddendumItemAttr) itemAttr).getFieldId();
					formula 	= ((BidDocAddendumItemAttr) itemAttr).getFormula();
					tableName	= "bid_doc_addendum_item_attr";
				} else if (itemAttr instanceof BidDocRevisionItemAttr) {
					formItemId 	= ((BidDocRevisionItemAttr) itemAttr).getFormItemId();
					fieldId 	= ((BidDocRevisionItemAttr) itemAttr).getFieldId();
					formula 	= ((BidDocRevisionItemAttr) itemAttr).getFormula();
					tableName	= "bid_doc_revision_item_attr";
				} else if (itemAttr instanceof BidDocumentsItemAttribute) {
					formItemId 	= ((BidDocumentsItemAttribute) itemAttr).getFormItemId();
					fieldId 	= ((BidDocumentsItemAttribute) itemAttr).getFieldId();
					formula 	= ((BidDocumentsItemAttribute) itemAttr).getFormula();
					tableName	= "bid_documents_item_attribute";
				} else if (itemAttr instanceof CItemAttribute) {
					formItemId 	= ((CItemAttribute) itemAttr).getFormItemId();
					fieldId 	= ((CItemAttribute) itemAttr).getFieldId();
					formula 	= ((CItemAttribute) itemAttr).getFormula();
					tableName	= "c_item_attribute";
				}
				
	            sbCondition.append("(")
     		   			   .append(formItemId)
     		   			   .append(",")
     		   			   .append(fieldId)
     		   			   .append("),");
	            
				sbWhen.append(" WHEN formula = '")
					  .append(formula)
					  .append("' THEN '")
					  .append(FxUtils.cm_adjustIdentifier(formula, compareNum, affectedFieldCount, isRow))
					  .append("' ");				
			}
			
	        sbWhen.append(" END ");	        
	        sbCondition.setLength(sbCondition.length() - 1);
	        sbCondition.append(")");
	        
	        StringBuilder sqlQuery = new StringBuilder();        
	        sqlQuery.append("UPDATE ")
	        		.append(tableName)
	        		.append(" set formula = ")
	        		.append(sbCase.toString())
	        		.append(sbWhen.toString())
	        		.append(sbWhere.toString())
	        		.append(sbCondition.toString());
	        
	        Query query = entityManager.createNativeQuery(sqlQuery.toString());	
	        query.executeUpdate();			
		}
	}
	
	public static <T extends Object> void bulkUpdateFormula(EntityManager entityManager, 
                                                            Collection<T> entities,
                                                            Map<String, String> formulaMap) throws Exception {
        if (entities.size() > 0) {
            StringBuilder sbWhere = new StringBuilder();
            StringBuilder sbCase = new StringBuilder();
            StringBuilder sbWhen = new StringBuilder();
            StringBuilder sbCondition = new StringBuilder();

            sbWhere.append(" WHERE (form_item_id, field_id) IN ");
            sbCase.append(" CASE ");
            sbCondition.append("(");

            Integer formItemId = 0;
            Integer fieldId = 0;
            String formula = "";
            String tableName = "";

            for (T itemAttr : entities) {
                if (itemAttr instanceof BidDocAddendumItemAttr) {
                    formItemId = ((BidDocAddendumItemAttr) itemAttr).getFormItemId();
                    fieldId = ((BidDocAddendumItemAttr) itemAttr).getFieldId();
                    formula = ((BidDocAddendumItemAttr) itemAttr).getFormula();
                    tableName = "bid_doc_addendum_item_attr";
                } else if (itemAttr instanceof BidDocRevisionItemAttr) {
                    formItemId = ((BidDocRevisionItemAttr) itemAttr).getFormItemId();
                    fieldId = ((BidDocRevisionItemAttr) itemAttr).getFieldId();
                    formula = ((BidDocRevisionItemAttr) itemAttr).getFormula();
                    tableName = "bid_doc_revision_item_attr";
                } else if (itemAttr instanceof BidDocumentsItemAttribute) {
                    formItemId = ((BidDocumentsItemAttribute) itemAttr).getFormItemId();
                    fieldId = ((BidDocumentsItemAttribute) itemAttr).getFieldId();
                    formula = ((BidDocumentsItemAttribute) itemAttr).getFormula();
                    tableName = "bid_documents_item_attribute";
                } else if (itemAttr instanceof CItemAttribute) {
                    formItemId = ((CItemAttribute) itemAttr).getFormItemId();
                    fieldId = ((CItemAttribute) itemAttr).getFieldId();
                    formula = ((CItemAttribute) itemAttr).getFormula();
                    tableName = "c_item_attribute";
                }

                sbCondition.append("(")
                           .append(formItemId).append(",")
                           .append(fieldId)
                           .append("),");

                sbWhen.append(" WHEN formula = '")
                      .append(formula)
                      .append("' THEN '")
                      .append(formulaMap.get(formula))
                      .append("' ");
            }

            sbWhen.append(" END ");
            sbCondition.setLength(sbCondition.length() - 1);
            sbCondition.append(")");

            StringBuilder sqlQuery = new StringBuilder();
            sqlQuery.append("UPDATE ")
                    .append(tableName)
                    .append(" set formula = ")
                    .append(sbCase.toString())
                    .append(sbWhen.toString())
                    .append(sbWhere.toString())
                    .append(sbCondition.toString());

            Query query = entityManager.createNativeQuery(sqlQuery.toString());
            query.executeUpdate();
        }
    }
	
	public static <T extends Object> void bulkUpdateSeq(EntityManager entityManager, 
                                                        Collection<T> entities,
                                                        Map<Integer, Integer> itemListSeqMap,
                                                        boolean isListSeq) throws Exception {
        if (entities.size() > 0) {
            StringBuilder sbWhere = new StringBuilder();
            StringBuilder sbCase = new StringBuilder();
            StringBuilder sbWhen = new StringBuilder();
            StringBuilder sbCondition = new StringBuilder();

            sbWhere.append(" WHERE (form_item_id) IN ");
            sbCase.append(" CASE ");
            sbCondition.append("(");

            Integer formItemId = 0;
            Integer listSeq = 0;
            String rowIdentifier = "";
            String tableName = "";

            for (T item : entities) {
                if (item instanceof BidDocumentsFormItems) {
                    formItemId = ((BidDocumentsFormItems) item).getFormItemId();
                    listSeq = ((BidDocumentsFormItems) item).getListSeq();
                    rowIdentifier = ((BidDocumentsFormItems) item).getRowIdentifier();
                    tableName = "bid_documents_form_items";
                } else if (item instanceof BidDocAddendumItem) {
                    formItemId = ((BidDocAddendumItem) item).getFormItemId();
                    listSeq = ((BidDocAddendumItem) item).getListSeq();
                    rowIdentifier = ((BidDocAddendumItem) item).getRowIdentifier();
                    tableName = "bid_doc_addendum_item";
                } else if (item instanceof BidDocRevisionFormItem) {
                    formItemId = ((BidDocRevisionFormItem) item).getFormItemId();
                    listSeq = ((BidDocRevisionFormItem) item).getListSeq();
                    rowIdentifier = ((BidDocRevisionFormItem) item).getRowIdentifier();
                    tableName = "bid_doc_revision_form_item";
                } else if (item instanceof CItem) {
                    formItemId = ((CItem) item).getFormItemId();
                    listSeq = ((CItem) item).getListSeq();
                    rowIdentifier = ((CItem) item).getRowIdentifier();
                    tableName = "c_item";
                }

                sbCondition.append("(")
                           .append(formItemId)
                           .append("),");

                if (isListSeq) {
                    sbWhen.append(" WHEN listseq = ")
                          .append(listSeq)
                          .append(" THEN ")
                          .append(itemListSeqMap.get(formItemId))
                          .append(" ");
                } else {
                    sbWhen.append(" WHEN row_identifier = '")
                          .append(rowIdentifier)
                          .append("' THEN '")
                          .append(itemListSeqMap.get(formItemId))
                          .append("' ");
                }
            }

            sbWhen.append(" END ");
            sbCondition.setLength(sbCondition.length() - 1);
            sbCondition.append(")");

            StringBuilder sqlQuery = new StringBuilder();
            sqlQuery.append("UPDATE ")
                    .append(tableName)
                    .append(isListSeq ? " set listseq = " : " set row_identifier = ")
                    .append(sbCase.toString())
                    .append(sbWhen.toString())
                    .append(sbWhere.toString())
                    .append(sbCondition.toString());

            Query query = entityManager.createNativeQuery(sqlQuery.toString());
            query.executeUpdate();
        }
    }
	
	public static <T extends BaseEntity> void bulkDeleteEntityList(EntityManager entityManager, Collection<T> entities) throws Exception {
		if (entities.size() > 0) {

			Class<T> obj = (Class<T>)entities.iterator().next().getClass();

			CriteriaBuilder cb = entityManager.getCriteriaBuilder();
	        // create delete
	        CriteriaDelete<T> delete = cb.createCriteriaDelete(obj);
	        // set the root class
	        Root<T> root = delete.from(obj);

	        // set where clause
			delete.where(root.in(entities));
	        // perform update
			int result = entityManager.createQuery(delete).executeUpdate();
		}
	}
}
