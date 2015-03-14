'''
CX decomposition with approximate leverage scores
'''

from rma_utils import compLevExact
import numpy as np
from numpy.linalg import norm
import logging
logger = logging.getLogger(__name__)

class CX:
    def __init__(self, matrix_A):
        self.matrix_A = matrix_A

    def get_lev(self, k, **kwargs):
        """
        compute column leverage scores
        """

        reo = 4
        q = kwargs.get('q')

        Pi = np.random.randn(self.matrix_A.m, 2*k);

        B = self.matrix_A.ltimes(Pi.T).T

        for i in range(q):
            logger.info('Computing leverage scores, at iteration {0}!'.format(i+1))
            if i % reo == reo-1:
                logger.info("Reorthogonalzing!")
                Q, R = np.linalg.qr(B)
                B = Q
            B = self.matrix_A.atamat(B)

        lev, self.p = compLevExact(B, k, 0)
        self.lev = self.p*k

        return self.lev, self.p

    def comp_idx(self, scheme='deterministic', r=10):
        #seleting rows based on self.lev
        #scheme can be either 'deterministic' or 'randomized'
        #r dentotes the number of rows to select
        if scheme == 'deterministic':
            self.idx = np.array(self.lev).argsort()[::-1][:r]
        elif scheme == 'randomized':
            bins = np.add.accumulate(self.p)
            self.idx = np.digitize(np.random.random_sample(r), bins)

        return self.idx

    def get_rows(self):
        #getting the selected rows back
        idx = self.idx
        rows = self.matrix_A.rdd.filter(lambda (key, row): key in idx).collect()
        self.R = np.array([row[1] for row in rows])

        return self.R.shape #shape of R is r by d

    def comp_err(self):
        #computing the reconstruction error
        Rinv = np.linalg.pinv(self.R) #its shape is d by r
        RRinv = np.dot(Rinv, self.R) #its shape is d by d
        temp = np.eye(self.matrix_A.n) - RRinv

        diff = np.sqrt( self.matrix_A.rtimes(temp,self.sc,True).map(lambda (key,row): norm(row)**2 ).sum() )
        #diff = np.sqrt( self.matrix_A.rdd.map(lambda row: xN(row, [temp.value])).reduce(add) )

        return diff
