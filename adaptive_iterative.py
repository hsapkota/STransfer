import warnings
warnings.filterwarnings('ignore')
#import pandas as pd
import numpy as np
from joblib import load
import os
import pathlib
from sklearn.ensemble import RandomForestRegressor, RandomForestClassifier
# from sklearn.preprocessing import StandardScaler

#root = '~/hpcn/network_probing/'
clf_dir = pathlib.Path('./trained_classifiers/').absolute()
reg_dir =  pathlib.Path('./trained_regressors/').absolute()

threshold = 10
min_points, max_points = 2, 15
classifiers = {}
regressors = {}
num_of_tree = 50


def regression_train():
    for n in range(min_points, max_points+1):
        file_path = "./trained_regressors/regressor_%d.joblib" % n
        if os.path.exists(file_path):
            regressors[n] = load(file_path)


def classification_train():
    for n in range(min_points, max_points+1):
        file_path = "./trained_classifiers/classifier_%d.joblib" % n
        if os.path.exists(file_path):
            classifiers[n] = load(file_path)


def is_predictable(test_data):
    n = len(test_data)
    test_value = np.reshape(test_data, (1,n))
    if classifiers[n].predict(test_value)[0]==1 or n==max_points:
        return True
    else:
        return False


def make_prediction(test_data):
    n = len(test_data)
    test_value = np.reshape(test_data, (1,n))
    return regressors[n].predict(test_value)[0]


#def evaluate(X,y):
#	times = []
#	errors = []
#	for i in range(len(X)):
#		for n in range(min_points, max_points+1):
#			try:
#				test_value = np.reshape(X[i, :n],(1,n))
#				if classifiers[n].predict(test_value)[0]==1 or n==max_points:
#					predicted = regressors[n].predict(test_value)[0]
#					original = y[i]
#					error_rate = np.abs((predicted - original)/original) * 100
#					times.append(n)
#					errors.append(error_rate)
#					break
#			except Exception as e:
#				raise e
#
#	print("Duration: {0}s\nError Rate: {1}%".format(np.round(np.mean(times), 2), np.round(np.mean(errors), 2)))
#

#def main():
#	df = pd.read_csv(root+'data/esnet.csv')
#	# df = df[(df.mean_throughput / df.stdv_throughput)>2]
#	df.dropna(inplace=True)
#
#	test_data = df.sample(frac=.2)
#	y_reg_test = test_data["mean_throughput"].values
#	# X_test = StandardScaler().fit(test_data.iloc[:, :max_points]).transform(test_data.iloc[:, :max_points])
#	X_test = test_data.iloc[:, :max_points].values
#
#	new_dataset = df.drop(test_data.index)
#	clf_train_data = new_dataset.sample(frac=.3)
#	clf_reg_test = clf_train_data["mean_throughput"].values
#	# X_train_clf = StandardScaler().fit(clf_train_data.iloc[:, :max_points]).transform(clf_train_data.iloc[:, :max_points])
#	X_train_clf = clf_train_data.iloc[:, :max_points].values
#
#	train_data = new_dataset.drop(clf_train_data.index)
#	y_reg_train = train_data["mean_throughput"].values
#	# X_train = StandardScaler().fit(train_data.iloc[:, :max_points]).transform(train_data.iloc[:, :max_points])
#	X_train = train_data.iloc[:, :max_points].values
#
#	regression_train(X_train, y_reg_train)
#	classification_train(X_train_clf, clf_reg_test)
#
#
#	# Incremental Prediction
#	for i in range(len(X_test)):
#		for n in range(min_points, max_points+1):
#			# print(is_predictable(X_test[i, :n]))
#			if is_predictable(X_test[i, :n]):
#				predicted = make_prediction(X_test[i, :n])
#				print("Index: {0}, Duration: {1}s, Predicted={2}MBps, Actual: {3}MBps".format(i, n,
#																							   np.round(predicted),
#																							   np.round(y_reg_test[i])))
#				break
#
#	## Evaluate
#	evaluate(X_test, y_reg_test)


#if __name__ == "__main__":
#	main()
 