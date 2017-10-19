import copy
from salmon import rel
from salmon import dag
import salmon.utils as utils


def create(relName, columns, storedWith):

    columns = [rel.Column(relName, colName, idx, typeStr, collusionSet)
               for idx, (colName, typeStr, collusionSet) in enumerate(columns)]
    outRel = rel.Relation(relName, columns, storedWith)
    op = dag.Create(outRel)
    return op


def aggregate(inputOpNode, outputName, groupColNames, overColName, aggregator, aggOutColName):

    assert isinstance(groupColNames, list)
    # Get input relation from input node
    inRel = inputOpNode.outRel

    # Get relevant columns and reset their collusion sets
    inCols = inRel.columns
    groupCols = [utils.find(inCols, groupColName)
                 for groupColName in groupColNames]
    for groupCol in groupCols:
        groupCol.collSets = set()
    overCol = utils.find(inCols, overColName)
    overCol.collSets = set()

    # Create output relation. Default column order is
    # key column first followed by column that will be
    # aggregated. Note that we want copies as these are
    # copies on the output relation and changes to them
    # shouldn't affect the original columns
    aggOutCol = copy.deepcopy(overCol)
    aggOutCol.name = aggOutColName
    outRelCols = [copy.deepcopy(groupCol) for groupCol in groupCols]
    outRelCols.append(copy.deepcopy(aggOutCol))
    outRel = rel.Relation(outputName, outRelCols, copy.copy(inRel.storedWith))
    outRel.updateColumns()

    # Create our operator node
    op = dag.Aggregate(outRel, inputOpNode, groupCols, overCol, aggregator)

    # Add it as a child to input node
    inputOpNode.children.add(op)

    return op

def index_aggregate(inputOpNode, outputName, groupColNames, overColName, aggregator, aggOutColName, indexOp, distKeysOp):

    agg_op = aggregate(inputOpNode, outputName, groupColNames, overColName, aggregator, aggOutColName)
    idx_agg_op = dag.IndexAggregate.fromAggregate(agg_op, indexOp, distKeysOp)

    inputOpNode.children.remove(agg_op)
    inputOpNode.children.add(idx_agg_op)
    
    indexOp.children.add(idx_agg_op)
    distKeysOp.children.add(idx_agg_op)

    idx_agg_op.parents.add(indexOp)
    idx_agg_op.parents.add(distKeysOp)

    return idx_agg_op


def flat_group(inputOpNode, outputName, groupColName, overColName, aggOutColName):

    # Get input relation from input node
    inRel = inputOpNode.outRel

    # Get relevant columns and reset their collusion sets
    inCols = inRel.columns
    groupCol = utils.find(inCols, groupColName)
    groupCol.collSets = set()
    overCol = utils.find(inCols, overColName)
    overCol.collSets = set()

    # Create output relation. Default column order is
    # key column first followed by column that will be
    # aggregated. Note that we want copies as these are
    # copies on the output relation and changes to them
    # shouldn't affect the original columns
    aggOutCol = copy.deepcopy(overCol)
    aggOutCol.name = aggOutColName
    outRelCols = [copy.deepcopy(groupCol)]
    outRelCols.append(copy.deepcopy(aggOutCol))
    outRel = rel.Relation(outputName, outRelCols, copy.copy(inRel.storedWith))
    outRel.updateColumns()

    # Create our operator node
    op = dag.FlatGroup(outRel, inputOpNode, groupCol, overCol)

    # Add it as a child to input node
    inputOpNode.children.add(op)

    return op


def project(inputOpNode, outputName, selectedColNames):

    # Get input relation from input node
    inRel = inputOpNode.outRel

    # Get relevant columns and create copies
    outRelCols = copy.deepcopy(inRel.columns)

    # Find all columns by name
    selectedCols = [utils.find(inRel.columns, colName)
                    for colName in selectedColNames]

    outRelCols = copy.deepcopy(selectedCols)
    for col in outRelCols:
        col.collSets = set()

    # Create output relation
    outRel = rel.Relation(outputName, outRelCols, copy.copy(inRel.storedWith))
    outRel.updateColumns()

    # Create our operator node
    op = dag.Project(outRel, inputOpNode, selectedCols)

    # Add it as a child to input node
    inputOpNode.children.add(op)

    return op

def distinct(inputOpNode, outputName, selectedColNames):

    # Get input relation from input node
    inRel = inputOpNode.outRel

    # Get relevant columns and create copies
    outRelCols = copy.deepcopy(inRel.columns)

    # Find all columns by name
    selectedCols = [utils.find(inRel.columns, colName)
                    for colName in selectedColNames]

    outRelCols = copy.deepcopy(selectedCols)
    for col in outRelCols:
        col.collSets = set()

    # Create output relation
    outRel = rel.Relation(outputName, outRelCols, copy.copy(inRel.storedWith))
    outRel.updateColumns()

    # Create our operator node
    op = dag.Distinct(outRel, inputOpNode, selectedCols)

    # Add it as a child to input node
    inputOpNode.children.add(op)

    return op

def divide(inputOpNode, outputName, targetColName, operands):

    # Get input relation from input node
    inRel = inputOpNode.outRel

    # Get relevant columns and create copies
    outRelCols = copy.deepcopy(inRel.columns)

    # Replace all column names with corresponding columns.
    operands = [utils.find(inRel.columns, op) if isinstance(
        op, str) else op for op in operands]
    for operand in operands:
        if hasattr(operand, "collSets"):
            operand.collSets = set()

    # if targetCol already exists, it will be at the 0th index of operands
    if targetColName == operands[0].name:
        targetColumn = utils.find(inRel.columns, targetColName)
        targetColumn.collSets = set()
    else:
        # TODO: figure out new column's collSets
        targetColumn = rel.Column(
            outputName, targetColName, len(inRel.columns), "INTEGER", set())
        outRelCols.append(targetColumn)

    # Create output relation
    outRel = rel.Relation(outputName, outRelCols, copy.copy(inRel.storedWith))
    outRel.updateColumns()

    # Create our operator node
    op = dag.Divide(outRel, inputOpNode, targetColumn, operands)

    # Add it as a child to input node
    inputOpNode.children.add(op)

    return op


def filter(inputOpNode, outputName, filterColName, operator, filterExpr):

    # Get input relation from input node
    inRel = inputOpNode.outRel

    # Get relevant columns and create copies
    outRelCols = copy.deepcopy(inRel.columns)

    # Get index of filter column
    filterCol = utils.find(inRel.columns, filterColName)
    filterCol.collSets = set()

    # Create output relation
    outRel = rel.Relation(outputName, outRelCols, copy.copy(inRel.storedWith))
    outRel.updateColumns()

    # Create our operator node
    op = dag.Filter(outRel, inputOpNode, filterCol, operator, filterExpr)

    # Add it as a child to input node
    inputOpNode.children.add(op)

    return op


def multiply(inputOpNode, outputName, targetColName, operands):

    # Get input relation from input node
    inRel = inputOpNode.outRel

    # Get relevant columns and create copies
    outRelCols = copy.deepcopy(inRel.columns)

    # Replace all column names with corresponding columns.
    operands = [utils.find(inRel.columns, op) if isinstance(
        op, str) else op for op in operands]
    for operand in operands:
        if hasattr(operand, "collSets"):
            operand.collSets = set()

    # if targetCol already exists, it will be at the 0th index of operands
    if targetColName == operands[0].name:
        targetColumn = utils.find(inRel.columns, targetColName)
        targetColumn.collSets = set()
    else:
        # TODO: figure out new column's collSets
        targetColumn = rel.Column(
            outputName, targetColName, len(inRel.columns), "INTEGER", set())
        outRelCols.append(targetColumn)

    # Create output relation
    outRel = rel.Relation(outputName, outRelCols, copy.copy(inRel.storedWith))
    outRel.updateColumns()

    # Create our operator node
    op = dag.Multiply(outRel, inputOpNode, targetColumn, operands)

    # Add it as a child to input node
    inputOpNode.children.add(op)

    return op


# TODO: is a self-join a problem?
def join(leftInputNode, rightInputNode, outputName, leftColNames, rightColNames):

    # TODO: technically this should take in a start index as well
    # This helper method takes in a relation, the key column of the join
    # and its index.
    # It returns a list of new columns with correctly merged collusion sets
    # for the output relation (in the same order as they appear on the input
    # relation but excluding the key column)
    def _colsFromRel(startIdx, relation, keyColIdxs):

        resultCols = []
        for num, col in enumerate(relation.columns):
            # Exclude key columns and add num from enumerate to start index
            if col.idx not in set(keyColIdxs):
                newCol = rel.Column(
                    outputName, col.getName(), num + startIdx - len(keyColIdxs), col.typeStr, set())
                resultCols.append(newCol)

        return resultCols

    assert isinstance(leftColNames, list)
    assert isinstance(rightColNames, list)

    # Get input relation from input nodes
    leftInRel = leftInputNode.outRel
    rightInRel = rightInputNode.outRel

    # Get columns from both relations
    leftCols = leftInRel.columns
    rightCols = rightInRel.columns

    # Get columns we will join on
    leftJoinCols = [utils.find(leftCols, leftColName)
                    for leftColName in leftColNames]
    rightJoinCols = [utils.find(rightCols, rightColName)
                     for rightColName in rightColNames]

    # # Get the key columns' merged collusion set
    # keyCollusionSet = utils.mergeCollusionSets(
    #     leftJoinCol.collusionSet, rightJoinCol.collusionSet)

    # Create new key columns
    outKeyCols = []
    for i in range(len(leftJoinCols)):
        outKeyCols.append(
            rel.Column(outputName, leftJoinCols[i].getName(), i, leftJoinCols[i].typeStr, set()))

    # Define output relation columns.
    # These will be the key columns followed
    # by all columns from left (other than join columns)
    # and right (again excluding join columns)

    startIdx = len(outKeyCols)
    # continueIdx will be (startIdx + len(leftInRel.columns) - len(leftJoinCols)),
    # which is just len(leftInRel.columns)
    continueIdx = len(leftInRel.columns)
    outRelCols = outKeyCols \
        + _colsFromRel(
            startIdx, leftInRel, [leftJoinCol.idx for leftJoinCol in leftJoinCols]) \
        + _colsFromRel(
            continueIdx, rightInRel, [rightJoinCol.idx for rightJoinCol in rightJoinCols])

    # The result of the join will be stored with the union
    # of the parties storing left and right
    outStoredWith = leftInRel.storedWith.union(rightInRel.storedWith)

    # Create output relation
    outRel = rel.Relation(outputName, outRelCols, outStoredWith)
    outRel.updateColumns()

    # Create join operator
    op = dag.Join(
        outRel,
        leftInputNode,
        rightInputNode,
        leftJoinCols,
        rightJoinCols
    )

    # Add it as a child to both input nodes
    leftInputNode.children.add(op)
    rightInputNode.children.add(op)

    return op


def concat(inputOpNodes, outputName, columnNames=None):

    # Make sure we have at least two input node as a
    # sanity check--could relax this in the future
    assert(len(inputOpNodes) >= 2)

    # Get input relations from input nodes
    inRels = [inputOpNode.outRel for inputOpNode in inputOpNodes]

    # Ensure that all input relations have same
    # number of columns
    numCols = len(inRels[0].columns)
    for inRel in inRels:
        assert(len(inRel.columns) == numCols)
    if columnNames is not None:
        assert(len(columnNames) == numCols)

    # Copy over columns from existing relation
    outRelCols = copy.deepcopy(inRels[0].columns)
    for (i, col) in enumerate(outRelCols):
        if columnNames is not None:
            col.name = columnNames[i]
        else:
            # we use the column names from the first input
            pass
        col.collSets = set()

    # The result of the concat will be stored with the union
    # of the parties storing the input relations
    inStoredWith = [inRel.storedWith for inRel in inRels]
    outStoredWith = set().union(*inStoredWith)

    # Create output relation
    outRel = rel.Relation(outputName, outRelCols, outStoredWith)
    outRel.updateColumns()

    # Create our operator node
    op = dag.Concat(outRel, inputOpNodes)

    # Add it as a child to each input node
    for inputOpNode in inputOpNodes:
        inputOpNode.children.add(op)

    return op


def index(inputOpNode, outputName, idxColName="index"):

    inRel = inputOpNode.outRel

    # Copy over columns from existing relation
    outRelCols = copy.deepcopy(inRel.columns)

    indexCol = rel.Column(
        outputName, idxColName, len(inRel.columns), "INTEGER", set())
    outRelCols = [indexCol] + outRelCols

    # Create output relation
    outRel = rel.Relation(outputName, outRelCols, copy.copy(inRel.storedWith))
    outRel.updateColumns()

    op = dag.Index(outRel, inputOpNode)
    # Add it as a child to input node
    inputOpNode.children.add(op)

    return op


def shuffle(inputOpNode, outputName):

    inRel = inputOpNode.outRel

    # Copy over columns from existing relation
    outRelCols = copy.deepcopy(inRel.columns)

    # Create output relation
    outRel = rel.Relation(outputName, outRelCols, copy.copy(inRel.storedWith))
    outRel.updateColumns()

    op = dag.Shuffle(outRel, inputOpNode)
    # Add it as a child to input node
    inputOpNode.children.add(op)

    return op


def collect(inputOpNode, targetParty):

    # Get input relation from input node
    inRel = inputOpNode.outRel
    inRel.storedWith = set([targetParty])


# Below functions are NOT part of the public API! Only used to simplify
# codegen testing

def _index_join(leftInputNode, rightInputNode, outputName, leftColNames, rightColNames, indexOpNode):

    join_op = join(leftInputNode, rightInputNode,
                   outputName, leftColNames, rightColNames)
    idx_join_op = dag.IndexJoin.fromJoin(join_op, indexOpNode)

    leftInputNode.children.remove(join_op)
    rightInputNode.children.remove(join_op)

    leftInputNode.children.add(idx_join_op)
    rightInputNode.children.add(idx_join_op)
    indexOpNode.children.add(idx_join_op)

    return idx_join_op


def _persist(inputOpNode, outputName):

    outRel = copy.deepcopy(inputOpNode.outRel)
    outRel.rename(outputName)
    persistOp = dag.Persist(outRel, inputOpNode)
    inputOpNode.children.add(persistOp)
    return persistOp


def _close(inputOpNode, outputName, targetParties):

    outRel = copy.deepcopy(inputOpNode.outRel)
    outRel.storedWith = targetParties
    outRel.rename(outputName)
    closeOp = dag.Close(outRel, inputOpNode)
    inputOpNode.children.add(closeOp)
    return closeOp


def _open(inputOpNode, outputName, targetParty):

    outRel = copy.deepcopy(inputOpNode.outRel)
    outRel.storedWith = set([targetParty])
    outRel.rename(outputName)
    openOp = dag.Open(outRel, inputOpNode)
    inputOpNode.children.add(openOp)
    return openOp
