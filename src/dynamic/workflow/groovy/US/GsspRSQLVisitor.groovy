package groovy.US

import java.util.HashMap

import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory

import cz.jirutka.rsql.parser.ast.Node
import cz.jirutka.rsql.parser.ast.AndNode
import cz.jirutka.rsql.parser.ast.ComparisonNode
import cz.jirutka.rsql.parser.ast.OrNode
import cz.jirutka.rsql.parser.ast.RSQLVisitor

/**
 * This groovy is used for parsing RSQL query params
 *
 * @author Shikhar Arora
 */

class GsspRSQLVisitor implements RSQLVisitor {

	Logger logger = LoggerFactory.getLogger(GsspRSQLVisitor)
	HashMap<String,String> paramMap=new HashMap<String,String>();

	@Override
	public Object visit(AndNode node, Object param) {
		logger.debug('param'+param)
		for(Node n:node.getChildren()) {
			n.accept(this)
		}
		return node
	}

	@Override
	public Object visit(OrNode node, Object param) {
		logger.debug('param'+param)
		for(Node n:node.getChildren()) {
			n.accept(this)
		}
		return node
	}

	@Override
	public Object visit(ComparisonNode node, Object param) {
		logger.debug('param'+param)
		paramMap.putAt(node.getSelector(),node.getArguments().iterator().join(","))
		return node
	}
	
	public def getMap()
	{
		return paramMap
	}
}
