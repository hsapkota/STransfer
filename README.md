# STransfer

Compile and Run STSender and STReceiver in sender and receiver respectively.
And then run STClient.py file as follows:
On Sender Side:
```bash
javac STSender.java;
java -Xmx3296m STSender;
```
On Receiver Side:
```bash
javac STReceiver.java;
java -Xmx3296m STSender;
```
On controller Side:
```bash
python STClient.py 192.168.1.8:52005/dir/of/the/files/to/send/form/ 192.168.1.7:53823/dir/where/to/save/the/files/on/receiver/side #(--conv=avg --method=GA --generation=5 --population=10) --> Optional
```
Optional variables which can be passed as parameter while running java functions are:
```bash
--method
--generation
--population
--conv
--convTime
```
Method refers to the method which will be used for finding optimal throughput. It can be either "GA" or "random".<br>
Generation refers to number of generation in genetic algorithm.<br>
Population refers to number of individual in each generation of GA.<br>
Conv refers to convergence function used in which getting the fitness of each individual. The variables for conv can be "avg" and "ar".<br>
convTime refers to total throughput data to calculate the average convergence time with.

And to run the python code, Scikit-learn, Scipy and Numpy will need to be installed.

