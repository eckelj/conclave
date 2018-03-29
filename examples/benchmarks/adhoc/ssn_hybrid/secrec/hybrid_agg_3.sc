import shared3p;
import shared3p_table_database;
import shared3p_matrix;
import stdlib;
import table_database;
import matrix;
import shared3p_join;
import shared3p_random;
import shared3p_sort;

domain pd_shared3p shared3p;

template <domain D : shared3p>
D uint32[[2]] stretch(D uint32[[2]] rel, uint stretchBy) {
    uint numRows = shape(rel)[0];
    uint numCols = shape(rel)[1];
    uint numVals = numRows * stretchBy;
    D uint32[[2]] stretched(numVals, numCols);
    for (uint r = 0; r < numRows; r+=1) {
        // TODO figure out how to do this w/o the inner loop
        for (uint c = 0; c < numCols; c+=1) {
            stretched[r * stretchBy : (r + 1) * stretchBy,c] = rel[r,c];
        }
    }
    return stretched;
}

template <domain D : shared3p>
D uint32[[2]] duplicate(D uint32[[2]] rel, uint numDups) {
    uint numRows = shape(rel)[0];
    uint numVals = numRows * numDups;
    D uint32[[2]] dupped(numVals, shape(rel)[1]);
    for (uint r = 0; r < numDups; r+=1) {
        dupped[r * numRows : (r + 1) * numRows,:] = rel[:,:];
    }
    return dupped;
}

template <domain D : shared3p>
D uint32[[2]] filterByKeepFlags(D uint32[[2]] rel, uint32[[1]] keepFlags) {
    uint nrows = shape(rel)[0];
    uint ncols = shape(rel)[1];
    uint numResultRows = 0;
    for (uint r = 0; r < nrows; r+=1) {
        if (keepFlags[r] == 1) {
            numResultRows++;
        }
    }
    D uint32 [[2]] finalResult(numResultRows,ncols);
    uint resIdx = 0;
    for (uint r = 0; r < nrows; r+=1) {
        if (keepFlags[r] == 1) {
            finalResult[resIdx,:] = rel[r,:];
            resIdx++;
        }
    }
    return finalResult;
}

template <domain D : shared3p>
D uint32[[2]] _obliviousJoin(D uint32[[2]] left, uint leftJoinColIdx, D uint32[[2]] right, uint rightJoinColIdx) {
    D uint32[[2]] leftShuffled = shuffleRows(left);
    D uint32[[2]] rightShuffled = shuffleRows(right);
    uint nrowsLeft = shape(left)[0];
    uint nrowsRight = shape(right)[0];
    D uint32[[2]] leftStretched = stretch(leftShuffled, nrowsRight);
    D uint32[[2]] rightDupped = duplicate(rightShuffled, nrowsLeft);
    D uint32[[1]] eqFlags = (uint32) (leftStretched[:,leftJoinColIdx] == rightDupped[:,rightJoinColIdx]);
    return joinFromEqFlags(eqFlags, leftStretched, leftJoinColIdx, rightDupped, rightJoinColIdx);
}

template <domain D : shared3p>
D uint32[[2]] join(D uint32[[2]] leftRel, uint[[1]] leftJoinCols,
    D uint32[[2]] rightRel, uint[[1]] rightJoinCols, uint[[1]] colsToKeep) {
    // TODO: assert join cols are singletons
    D uint32[[2]] res = _obliviousJoin(leftRel, leftJoinCols[0], rightRel, rightJoinCols[0]);
    return project(res, colsToKeep);
}

template <domain D : shared3p>
D uint32[[2]] joinFromEqFlags(D uint32[[1]] eqFlags, D uint32[[2]] left, uint leftJoinColIdx,
        D uint32[[2]] right, uint rightJoinColIdx) {
    uint ncolsLeft = shape(left)[1];
    uint ncolsRight = shape(right)[1];
    D uint32[[2]] withFlags(size(eqFlags), 1 + ncolsLeft + ncolsRight);
    withFlags[:, 0 : ncolsLeft] = left[:,0 : ncolsLeft];
    withFlags[:, ncolsLeft : ncolsLeft + ncolsRight] = right[:,0 : ncolsRight];
    withFlags[:,ncolsLeft + ncolsRight] = eqFlags;
    D uint32[[2]] shuffledWithFlags = shuffleRows(withFlags);
    uint32[[1]] keepFlags = declassify(shuffledWithFlags[:,ncolsLeft + ncolsRight]);
    return filterByKeepFlags(shuffledWithFlags[:,0:ncolsLeft + ncolsRight], keepFlags);
}

template <domain D : shared3p>
D uint32[[2]] joinLeaky(D uint32[[2]] leftRel, uint[[1]] leftJoinCols,
    D uint32[[2]] rightRel, uint[[1]] rightJoinCols, uint[[1]] colsToKeep) {
    // TODO: assert join cols are singletons
    // perform native join
    D uint32[[2]] res = (uint32) tableJoinAes128(
        (xor_uint32) leftRel,
        leftJoinCols[0],
        (xor_uint32) rightRel,
        rightJoinCols[0]
    );
    return project(res, colsToKeep);
}

template <domain D : shared3p>
D uint32[[2]] aggregateSum(D uint32[[2]] rows, uint keyCol, uint valCol) {
    uint nrows = shape(rows)[0];
    uint ncols = 2;

    D uint32 [[2]] input(nrows, ncols);
    input[:,0] = rows[:,keyCol];
    input[:,1] = rows[:,valCol];

    D uint32 [[2]] sorted = sort(input, (uint)0);
    D uint32 [[2]] result(nrows,ncols + 1);
    result[:,0] = sorted[:,0];
    result[:,1] = sorted[:,1];

    pd_shared3p uint32[[1]] leftKeyCols = result[0:nrows-1,0];
    pd_shared3p uint32[[1]] rightKeyCols = result[1:,0];
    pd_shared3p uint32[[1]] eqFlags = (uint32)(leftKeyCols == rightKeyCols);
    for (uint r = 0; r < nrows - 1; r+=1) {
        D uint32[[1]] left = result[r,:];
        D uint32[[1]] right = result[r + 1,:];
        D uint32 eqFlag = eqFlags[r];
        D uint32 leftVal = left[1];
        D uint32 rightVal = right[1];
        result[r, 1] = leftVal * (1 - eqFlag);
        result[r + 1, 1] = rightVal + leftVal * (eqFlag);
        result[r, 2] = 1 - eqFlag;
        result[r + 1, 2] = 1 - eqFlag;
    }
    // always keep last row
    result[nrows - 1, 2] = 1;
    D uint32 [[2]] shuffledWithFlags = shuffleRows(result);
    uint32 [[1]] keepFlags = declassify(shuffledWithFlags[:,2]);
    return filterByKeepFlags(shuffledWithFlags[:,0:2], keepFlags);;
}

template <domain D : shared3p>
D uint32[[2]] indexAggregateNonLeaky(D uint32[[2]] rows, uint keyCol, uint valCol,
        D uint32[[2]] wrappedEqFlags, D uint32[[2]] keysWithIndeces) {
    uint nrows = shape(rows)[0];
    uint ncols = 2;

    D uint32 [[2]] input(nrows, ncols);
    input[:,0] = rows[:,keyCol];
    input[:,1] = rows[:,valCol];

    D uint32 [[1]] eqFlags = wrappedEqFlags[:,0];
    uint32 [[1]] newIndeces = declassify(keysWithIndeces[:,0]);
    D uint32 [[2]] sorted(nrows, ncols);
    for (uint r = 0; r < nrows; r+=1) {
        sorted[r,:] = input[(uint)newIndeces[r],:];
    }

    for (uint r = 0; r < nrows - 1; r+=1) {
        D uint32[[1]] left = sorted[r,:];
        D uint32[[1]] right = sorted[r + 1,:];
        D uint32 eqFlag = eqFlags[r];

        D uint32 leftVal = left[1];
        D uint32 rightVal = right[1];
        sorted[r, 1] = leftVal * (1 - eqFlag);
        sorted[r + 1,1] = rightVal + leftVal * (eqFlag);
    }
    // TODO re-use code from regular aggregation
    D uint32[[1]] zeroValFlags = (uint32)(sorted[:,1] != 0);
    D uint32 [[2]] result(nrows,ncols + 1);
    result[:,0] = sorted[:,0];
    result[:,1] = sorted[:,1];
    result[:,2] = zeroValFlags[:];
    D uint32 [[2]] shuffled = shuffleRows(result);

    uint32 [[1]] keepFlags = declassify(shuffled[:,2]);
    uint numResultRows = 0;
    for (uint r = 0; r < nrows; r+=1) {
        if (keepFlags[r] == 1) {
            numResultRows++;
        }
    }
    D uint32 [[2]] finalResult(numResultRows,ncols);
    uint resIdx = 0;
    for (uint r = 0; r < nrows; r+=1) {
        if (keepFlags[r] == 1) {
            finalResult[resIdx,0] = shuffled[r,0];
            finalResult[resIdx,1] = shuffled[r,1];
            resIdx++;
        }
    }
    return finalResult;
}

template <domain D : shared3p>
D uint32[[2]] project(D uint32[[2]] rows, uint[[1]] selectedCols) {
    uint nrows = shape(rows)[0];
    uint ncols = size(selectedCols);
    D uint32 [[2]] projected(nrows, ncols);
    for (uint c = 0; c < ncols; ++c) {
        projected[:, c] = rows[:, selectedCols[c]];
    }
    return projected;
}

template <domain D : shared3p>
D uint32[[2]] multiply(D uint32[[2]] rows, uint targetColIdx, uint[[1]] operands, uint[[1]] scalarFlags) {
    D uint32 [[2]] res = rows;
    if (scalarFlags[0] == 0) {
        // column operand
        res[:, targetColIdx] = rows[:, operands[0]];
    }
    else {
        // scalar operand
        D uint32 scalar = (uint32) operands[0];
        res[:, targetColIdx] = scalar;
    }
    for (uint c = 1; c < size(operands); ++c) {
        if (scalarFlags[c] == 0) {
            // column operand
            res[:, targetColIdx] = res[:, targetColIdx] * rows[:, operands[c]];
        }
        else {
            // scalar operand
            D uint32 scalar = (uint32) operands[c];
            res[:, targetColIdx] = res[:, targetColIdx] * scalar;
        }
    }
    return res;
}

template <domain D : shared3p>
D uint32[[2]] divide(D uint32[[2]] rows, uint targetColIdx, uint[[1]] operands, uint[[1]] scalarFlags) {
    D uint32 [[2]] divided = rows;
    if (scalarFlags[0] == 0) {
        // column operand
        divided[:, targetColIdx] = rows[:, operands[0]];
    }
    else {
        // scalar operand
        D uint32 scalar = (uint32) operands[0];
        divided[:, targetColIdx] = scalar;
    }
    for (uint c = 1; c < size(operands); ++c) {
        if (scalarFlags[c] == 0) {
            // column operand
            divided[:, targetColIdx] = divided[:, targetColIdx] / rows[:, operands[c]];
        }
        else {
            // scalar operand
            D uint32 scalar = (uint32) operands[c];
            divided[:, targetColIdx] = divided[:, targetColIdx] / scalar;
        }
    }
    return divided;
}

pd_shared3p uint32 [[2]] readFromDb(string ds, string tbl) {
    uint ncols = tdbGetColumnCount(ds, tbl);
    uint nrows = tdbGetRowCount(ds, tbl);

    pd_shared3p uint32 [[2]] mat(nrows, ncols);
    for (uint c = 0; c < ncols; ++c) {
        pd_shared3p uint32 [[1]] col = tdbReadColumn(ds, tbl, c);
        for (uint r = 0; r < nrows; ++r) {
            mat[r, c] = col[r];
        }
    }
    return mat;
}

template <domain D : shared3p>
void persist(string ds, string tableName, D uint32[[2]] rows) {
    uint nrows = shape(rows)[0];
    uint ncols = shape(rows)[1];
    if (tdbTableExists(ds, tableName)) {
        tdbTableDelete(ds, tableName);
    }
    pd_shared3p uint32 vtype;
    tdbTableCreate(ds, tableName, vtype, ncols);
    uint params = tdbVmapNew();
    for (uint rowIdx = 0; rowIdx < nrows; ++rowIdx) {
        if (rowIdx != 0) {
            // This has to be called in-between rows
            tdbVmapAddBatch(params);
        }
        tdbVmapAddValue(params, "values", rows[rowIdx,:]);
    }
    tdbInsertRow(ds, tableName, params);
    tdbVmapDelete(params);
}

template <domain D : shared3p>
D uint32[[2]] indexJoin(D uint32[[2]] leftRel, uint leftJoinCol, D uint32[[2]] rightRel,
        uint rightJoinCol, uint32[[2]] indeces) {
    uint nrows = shape(indeces)[0];
    uint ncolsLeft = shape(leftRel)[1];
    uint ncolsRight = shape(rightRel)[1];
    uint ncolsRes = ncolsLeft + ncolsRight - 1;
    pd_shared3p uint32 [[2]] result(nrows, ncolsRes);
    for (uint r = 0; r < nrows; ++r) {
        uint lidx = (uint) indeces[r, 0];
        uint ridx = (uint) indeces[r, 1];
        for (uint c = 0; c < ncolsLeft; ++c) {
            result[r,c] = leftRel[lidx,c];
        }
        uint offset = ncolsLeft;
        uint nextIdx = 0;
        for (uint c = 0; c < ncolsRight; ++c) {
            if (c != rightJoinCol) {
                result[r,nextIdx + offset] = rightRel[ridx,c];
                nextIdx++;
            }
        }
    }
    return shuffleRows(result);
}

template <domain D : shared3p>
D uint32[[2]] indexAggregateSum(D uint32[[2]] rows, uint valCol, D uint32[[2]] keys, uint32[[2]] indeces) {
    uint nkeys = shape(keys)[0];
    uint nrows = shape(rows)[0];
    uint ncols = 2;

    D uint32 [[2]] res(nkeys, ncols);
    res[:,0] = keys[:,0];
    res[:,1] = 0;

    for (uint r = 0; r < nrows; r+=1) {
        uint rowIdx = (uint)indeces[r,0];
        uint keyIdx = (uint)indeces[r,1];
        res[keyIdx,1] = res[keyIdx,1] + rows[rowIdx,valCol];
    }

    return res;
}

template <domain D : shared3p>
D uint32[[2]] flagJoin(D uint32[[2]] eqFlags, D uint32[[2]] left, uint leftJoinCol, D uint32[[2]] right,
        uint rightJoinCol, uint[[1]] colsToKeep) {
    uint nrowsLeft = shape(left)[0];
    uint nrowsRight = shape(right)[0];
    D uint32[[2]] leftStretched = stretch(left, nrowsRight);
    D uint32[[2]] rightDupped = duplicate(right, nrowsLeft);
    D uint32[[2]] res = joinFromEqFlags(eqFlags[:,0], leftStretched, leftJoinCol, rightDupped, rightJoinCol);
    return project(res, colsToKeep);
}

void main() {
    print("Running hybrid_ssn hybrid_agg_3");
    string ds = "DS1";
    tdbOpenConnection(ds);
    pd_shared3p uint32 [[2]] eq_flags = readFromDb("DS1", "eq_flags");
    pd_shared3p uint32 [[2]] joined_and_shuffled = readFromDb("DS1", "joined_and_shuffled");
    pd_shared3p uint32 [[2]] sorted_by_key = readFromDb("DS1", "sorted_by_key");
    pd_shared3p uint32 [[2]] ssn_hybrid_result = indexAggregateNonLeaky(joined_and_shuffled,
        (uint) 0,
        (uint) 1,
        eq_flags,
        sorted_by_key);
    print("Will publish ssn_hybrid_result with size: ");
    print(shape(ssn_hybrid_result)[0]);
    publish("ssn_hybrid_result", declassify(ssn_hybrid_result));
    tdbCloseConnection(ds);
}